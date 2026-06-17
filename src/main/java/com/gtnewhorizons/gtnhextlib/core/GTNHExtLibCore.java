package com.gtnewhorizons.gtnhextlib.core;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.falsepattern.deploader.DeploaderStub;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.TransformerExclusions({ "com.gtnewhorizons.gtnhextlib", "com.falsepattern.deploader",
        "it.unimi.dsi.fastutil", "org.joml", "com.mojang.brigadier", "xyz.wagyourtail.jvmdowngrader" })
public class GTNHExtLibCore implements IFMLLoadingPlugin {

    private static final Logger LOG = LogManager.getLogger("GTNHExtLib");

    private static final String SIGNATURE_RESOURCE = "META-INF/gtnhextlib_deps.json";
    private static final String FAT_MARKER_PREFIX = "META-INF/falsepatternlib_repo/it/unimi/dsi/fastutil/";
    private static final String JVMDG_SYSCL_MARKER = "gtnhlib.jvmdg.systemClassLoader";
    private static final String JVMDG_ARTIFACT = "jvmdowngrader-java-api";
    private static final String RFB_PACKAGE = "com.gtnewhorizons.retrofuturabootstrap";

    static {
        try {
            final Field cleF = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
            cleF.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> cle = (Set<String>) cleF.get(Launch.classLoader);
            // for Brigadier
            cle.remove("com.mojang.");
            // Thermos console log compat
            boolean hybridServer = Launch.classLoader.getResource("org/bukkit/World.class") != null
                    || Launch.classLoader.getResource("thermos/Thermos.class") != null;
            if (hybridServer) {
                cle.add("com.mojang.util.QueueLogAppender");
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DeploaderStub.bootstrap(false);
        DeploaderStub.runDepLoader();
        mirrorJvmdgStubToSystemClassLoader();
    }

    private static void mirrorJvmdgStubToSystemClassLoader() {
        if (Launch.blackboard.get(JVMDG_SYSCL_MARKER) != null) {
            return;
        }
        final ClassLoader scl = ClassLoader.getSystemClassLoader();
        if (!(scl instanceof URLClassLoader) || scl.getClass().getName().startsWith(RFB_PACKAGE)) {
            return;
        }
        try {
            final Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            int mirrored = 0;
            for (URL url : Launch.classLoader.getSources()) {
                final String path = url.getPath();
                if (path == null || !path.contains(JVMDG_ARTIFACT)) continue;

                addURL.invoke(scl, url);
                mirrored++;
                LOG.info("Mirrored jvmdg stub {} onto system classloader", url);
            }
            if (mirrored == 0) {
                LOG.warn("jvmdg stub not found on LaunchClassLoader sources; system-classloader mirror skipped");
            } else {
                Launch.blackboard.put(JVMDG_SYSCL_MARKER, Boolean.TRUE);
            }
        } catch (ReflectiveOperationException e) {
            LOG.error("Failed to mirror jvmdg stub onto system classloader", e);
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        try {
            final Object location = data.get("coremodLocation");
            if (!(location instanceof File)) {
                // dev environment: no physical coremod jar
                return;
            }
            final File ourJar = ((File) location).getCanonicalFile();
            ClassLoader cl = (ClassLoader) data.get("classLoader");
            if (!(cl instanceof LaunchClassLoader)) {
                cl = Launch.classLoader;
            }
            final List<File> peers = findExtLibJars((LaunchClassLoader) cl);
            if (peers.size() <= 1) {
                return;
            }
            final File winner = pickWinner(peers);
            if (!ourJar.equals(winner.getCanonicalFile())) {
                LOG.info(
                        "Multiple GTNHExtLib jars detected; suppressing this one ({}) in favour of {}",
                        ourJar.getName(),
                        winner.getName());
                suppressFromModScan(ourJar.getName());
            } else {
                LOG.info("Multiple GTNHExtLib jars detected; this one ({}) wins", ourJar.getName());
            }
        } catch (Throwable t) {
            LOG.error("GTNHExtLib self-dedup failed", t);
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    private static List<File> findExtLibJars(LaunchClassLoader lcl) {
        final List<File> out = new ArrayList<>();
        for (URL url : lcl.getSources()) {
            final File f = urlToJarFile(url);
            if (f == null || !f.isFile()) {
                continue;
            }
            try (JarFile jf = new JarFile(f)) {
                if (jf.getEntry(SIGNATURE_RESOURCE) != null) {
                    out.add(f.getCanonicalFile());
                }
            } catch (IOException ignored) {
                // not a readable jar; skip
            }
        }
        return out;
    }

    private static File urlToJarFile(URL url) {
        String s = url.toString();
        if (s.startsWith("jar:")) {
            s = s.substring(4);
        }
        final int bang = s.indexOf("!/");
        if (bang >= 0) {
            s = s.substring(0, bang);
        }
        if (!s.startsWith("file:")) {
            return null;
        }
        try {
            return new File(new URI(s));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static File pickWinner(List<File> peers) {
        final List<File> sorted = new ArrayList<>(peers);
        sorted.sort(
                Comparator.comparing(GTNHExtLibCore::parseVersion, GTNHExtLibCore::compareVersion).reversed()
                        .thenComparing(Comparator.comparing(GTNHExtLibCore::isFatJar).reversed())
                        .thenComparing(File::getName));
        return sorted.get(0);
    }

    private static int[] parseVersion(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            final Manifest m = jf.getManifest();
            if (m != null) {
                final String v = m.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (v != null && !v.isEmpty()) {
                    return parseSemver(v);
                }
            }
        } catch (IOException ignored) {}
        return new int[] { 0, 0, 0 };
    }

    private static int[] parseSemver(String v) {
        // Strip any pre-release/build suffix; treat numeric major.minor.patch only.
        final int dash = v.indexOf('-');
        if (dash >= 0) {
            v = v.substring(0, dash);
        }
        final int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        final String[] parts = v.split("\\.");
        final int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static int compareVersion(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static boolean isFatJar(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            return jf.stream().anyMatch(e -> e.getName().startsWith(FAT_MARKER_PREFIX));
        } catch (IOException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void suppressFromModScan(String jarFilename) throws ReflectiveOperationException {
        final Class<?> cmm = Class.forName("cpw.mods.fml.relauncher.CoreModManager");
        final Field reparsed = cmm.getDeclaredField("reparsedCoremods");
        reparsed.setAccessible(true);
        ((List<String>) reparsed.get(null)).remove(jarFilename);

        final Field loaded = cmm.getDeclaredField("loadedCoremods");
        loaded.setAccessible(true);
        final List<String> loadedList = (List<String>) loaded.get(null);
        if (!loadedList.contains(jarFilename)) {
            loadedList.add(jarFilename);
        }
    }
}
