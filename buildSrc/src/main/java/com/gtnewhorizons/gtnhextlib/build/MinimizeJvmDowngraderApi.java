package com.gtnewhorizons.gtnhextlib.build;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import xyz.wagyourtail.jvmdg.cli.Flags;
import xyz.wagyourtail.jvmdg.compile.ApiShader;
import xyz.wagyourtail.jvmdg.compile.shade.ReferenceGraph;
import xyz.wagyourtail.jvmdg.logging.Logger;
import xyz.wagyourtail.jvmdg.util.Pair;
import xyz.wagyourtail.jvmdg.version.map.FullyQualifiedMemberNameAndDesc;
import xyz.wagyourtail.jvmdg.version.map.MemberNameAndDesc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

@CacheableTask
public abstract class MinimizeJvmDowngraderApi extends DefaultTask {
    private static final long ZIP_EPOCH = 315532800000L;
    private static final String API_PREFIX = "xyz/wagyourtail/jvmdg/";

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUsageReport();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputApiJar();

    @Input
    public abstract Property<String> getJavaTarget();

    @Input
    public abstract Property<String> getExpectedSha256();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputDirectory
    public abstract DirectoryProperty getAuditDirectory();

    @TaskAction
    public void minimize() throws Exception {
        Path reportPath = getUsageReport().get().getAsFile().toPath();
        Path apiJar = getInputApiJar().get().getAsFile().toPath();
        Path outputJar = getOutputJar().get().getAsFile().toPath();
        Path auditDirectory = getAuditDirectory().get().getAsFile().toPath();
        verifySha256(apiJar, getExpectedSha256().get());
        Map<?, ?> report = asMap(new JsonSlurper().parse(reportPath.toFile()), "report");
        validateReport(report);

        Set<FullyQualifiedMemberNameAndDesc> starts = reportRoots(report);
        int reportRootCount = starts.size();
        Flags flags = new Flags();
        flags.logAnsiColors = false;
        flags.logLevel = Logger.Level.WARN;

        Files.createDirectories(outputJar.getParent());
        Files.createDirectories(auditDirectory);

        Set<String> retainedClasses = new TreeSet<>();
        Set<String> removedClasses = new TreeSet<>();
        Set<String> sourceMissingReportMembers;
        Set<String> requiredResources;
        OutputState outputState;
        int inputClasses;
        int inputMethods = 0;
        int inputFields = 0;
        int metadataSupportRoots = 0;

        URI apiUri = URI.create("jar:" + apiJar.toUri());
        try (FileSystem apiFs = FileSystems.newFileSystem(apiUri, Collections.singletonMap("create", "false"))) {
            Pair<ReferenceGraph, Set<Type>> scan =
                ApiShader.scanApis(flags, Collections.singletonList(apiFs.getPath("/")));
            ReferenceGraph graph = scan.getFirst();
            Set<Type> available = scan.getSecond();
            inputClasses = available.size();
            Map<String, Type> availableByName = new HashMap<>();
            for (Type type : available) {
                ClassNode node = graph.getClassFor(type, 0);
                inputMethods += node.methods.size();
                inputFields += node.fields.size();
                availableByName.put(type.getInternalName(), type);
                if (type.getInternalName().startsWith(API_PREFIX + "version/")
                    && (node.access & Opcodes.ACC_ANNOTATION) != 0
                    && starts.add(FullyQualifiedMemberNameAndDesc.of(type))) {
                    metadataSupportRoots++;
                }
            }

            Set<Type> missingRoots = new TreeSet<>(Comparator.comparing(Type::getInternalName));
            for (FullyQualifiedMemberNameAndDesc start : starts) {
                if (!available.contains(start.getOwner())) {
                    missingRoots.add(start.getOwner());
                }
            }
            if (!missingRoots.isEmpty()) {
                throw new GradleException("Report roots absent from " + apiJar.getFileName() + ": " + missingRoots);
            }
            sourceMissingReportMembers = missingMemberRoots(starts, graph);
            if (!sourceMissingReportMembers.isEmpty()) {
                getLogger().warn(
                    "{} report members are absent from {} and cannot be retained: {}",
                    sourceMissingReportMembers.size(),
                    apiJar.getFileName(),
                    sourceMissingReportMembers);
            }

            while (true) {
                Pair<Set<FullyQualifiedMemberNameAndDesc>, Set<String>> closure = graph.recursiveResolveFrom(starts, 0);
                Map<Type, Set<MemberNameAndDesc>> byType = byType(closure.getFirst());
                outputState = render(graph, byType);
                Set<Type> referenced = internalReferences(outputState.classes, availableByName);
                referenced.removeAll(byType.keySet());
                if (referenced.isEmpty()) {
                    requiredResources = new TreeSet<>(closure.getSecond());
                    for (Type type : available) {
                        if (byType.containsKey(type)) {
                            retainedClasses.add(type.getClassName());
                        } else {
                            removedClasses.add(type.getClassName());
                        }
                    }
                    break;
                }
                for (Type type : referenced) {
                    starts.add(FullyQualifiedMemberNameAndDesc.of(type));
                }
            }
        }

        writeJar(apiJar, outputJar, outputState.classes, requiredResources);
        writeLines(auditDirectory.resolve("retained-classes.txt"), retainedClasses);
        writeLines(auditDirectory.resolve("removed-classes.txt"), removedClasses);
        writeLines(auditDirectory.resolve("required-resources.txt"), requiredResources);
        writeLines(auditDirectory.resolve("source-missing-report-members.txt"), sourceMissingReportMembers);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schema_version", 1);
        summary.put("build_id", report.get("build_id"));
        summary.put("java_target", getJavaTarget().get());
        summary.put("source_jar", apiJar.getFileName().toString());
        summary.put("source_sha256", getExpectedSha256().get().toLowerCase());
        summary.put("source_bytes", Files.size(apiJar));
        summary.put("output_jar", outputJar.getFileName().toString());
        summary.put("output_bytes", Files.size(outputJar));
        summary.put("output_sha256", sha256(outputJar));
        summary.put("cache_classifier", "downgraded-" + getJavaTarget().get() + "-" + cacheSuffix(outputJar));
        summary.put("report_sha256", sha256(reportPath));
        summary.put("report_roots", reportRootCount);
        summary.put("metadata_support_roots", metadataSupportRoots);
        summary.put("source_missing_report_members", sourceMissingReportMembers);
        summary.put("internal_reference_roots", starts.size() - reportRootCount - metadataSupportRoots);
        summary.put("input_classes", inputClasses);
        summary.put("retained_classes", retainedClasses.size());
        summary.put("removed_classes", removedClasses.size());
        summary.put("input_methods", inputMethods);
        summary.put("retained_methods", outputState.methods);
        summary.put("input_fields", inputFields);
        summary.put("retained_fields", outputState.fields);
        summary.put("required_resources", requiredResources.size());
        Files.write(
            auditDirectory.resolve("summary.json"),
            (JsonOutput.prettyPrint(JsonOutput.toJson(summary)) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

        getLogger().lifecycle(
            "JvmDowngrader Java {}: retained {}/{} classes and {}/{} methods ({} -> {} bytes)",
            getJavaTarget().get(),
            retainedClasses.size(),
            inputClasses,
            outputState.methods,
            inputMethods,
            Files.size(apiJar),
            Files.size(outputJar));
    }

    private void validateReport(Map<?, ?> report) {
        if (!"1".equals(String.valueOf(report.get("schema_version")))) {
            throw new GradleException("Unsupported JvmDowngrader usage report schema: " + report.get("schema_version"));
        }
        if (!getJavaTarget().get().equals(String.valueOf(report.get("java_target")))) {
            throw new GradleException(
                "Expected a Java " + getJavaTarget().get() + " usage report, got Java " + report.get("java_target"));
        }
        List<?> errors = asList(report.get("errors"), "errors");
        if (!errors.isEmpty()) {
            throw new GradleException("Refusing to minimize from a report containing scan errors: " + errors);
        }
    }

    private static Set<FullyQualifiedMemberNameAndDesc> reportRoots(Map<?, ?> report) {
        Set<FullyQualifiedMemberNameAndDesc> roots = new HashSet<>();
        for (Object value : asList(report.get("class_references"), "class_references")) {
            Map<?, ?> root = asMap(value, "class reference");
            roots.add(FullyQualifiedMemberNameAndDesc.of(objectType(root.get("class"))));
        }
        for (Object value : asList(report.get("member_references"), "member_references")) {
            Map<?, ?> root = asMap(value, "member reference");
            roots.add(new FullyQualifiedMemberNameAndDesc(
                objectType(root.get("owner")),
                String.valueOf(root.get("name")),
                Type.getType(String.valueOf(root.get("descriptor")))));
        }
        for (Object value : asList(report.get("possible_dynamic_references"), "possible_dynamic_references")) {
            Map<?, ?> root = asMap(value, "dynamic reference");
            roots.add(FullyQualifiedMemberNameAndDesc.of(objectType(root.get("value"))));
        }
        if (roots.isEmpty()) {
            throw new GradleException("JvmDowngrader usage report contains no roots");
        }
        return roots;
    }

    private static Type objectType(Object value) {
        String name = String.valueOf(value);
        if (name.startsWith("L") && name.endsWith(";")) {
            return Type.getType(name);
        }
        return Type.getObjectType(name.replace('.', '/'));
    }

    private static Map<Type, Set<MemberNameAndDesc>> byType(Set<FullyQualifiedMemberNameAndDesc> references) {
        Map<Type, Set<MemberNameAndDesc>> result = new HashMap<>();
        for (FullyQualifiedMemberNameAndDesc reference : references) {
            Set<MemberNameAndDesc> members = result.computeIfAbsent(reference.getOwner(), ignored -> new HashSet<>());
            if (!reference.isClassRef()) {
                members.add(reference.toMemberNameAndDesc());
            }
        }
        return result;
    }

    private static Set<String> missingMemberRoots(
        Set<FullyQualifiedMemberNameAndDesc> roots,
        ReferenceGraph graph) {
        Set<String> missing = new TreeSet<>();
        for (FullyQualifiedMemberNameAndDesc root : roots) {
            if (root.isClassRef()) {
                continue;
            }
            ClassNode node = graph.getClassFor(root.getOwner(), 0);
            MemberNameAndDesc member = root.toMemberNameAndDesc();
            boolean found = node.methods.stream().anyMatch(method -> member.equals(MemberNameAndDesc.fromNode(method)))
                || node.fields.stream().anyMatch(field -> member.equals(MemberNameAndDesc.fromNode(field)));
            if (!found) {
                missing.add(root.toString());
            }
        }
        return missing;
    }

    private static OutputState render(ReferenceGraph graph, Map<Type, Set<MemberNameAndDesc>> byType) {
        Map<Type, byte[]> classes = new HashMap<>();
        int methods = 0;
        int fields = 0;
        for (Map.Entry<Type, Set<MemberNameAndDesc>> entry : byType.entrySet()) {
            ClassNode source = graph.getClassFor(entry.getKey(), 0);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            source.accept(node);
            if ((node.access & Opcodes.ACC_ENUM) == 0) {
                node.methods.removeIf(method -> !entry.getValue().contains(MemberNameAndDesc.fromNode(method)));
                node.fields.removeIf(field -> !entry.getValue().contains(MemberNameAndDesc.fromNode(field)));
            }
            methods += node.methods.size();
            fields += node.fields.size();
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            classes.put(entry.getKey(), writer.toByteArray());
        }
        return new OutputState(classes, methods, fields);
    }

    @SuppressWarnings("deprecation")
    static Set<Type> internalReferences(Map<Type, byte[]> classes, Map<String, Type> availableByName) {
        Set<Type> references = new HashSet<>();
        Set<String> absent = new TreeSet<>();
        Remapper collector = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.startsWith(API_PREFIX)) {
                    Type type = availableByName.get(internalName);
                    if (type == null) {
                        absent.add(internalName);
                    } else {
                        references.add(type);
                    }
                }
                return internalName;
            }
        };
        for (byte[] bytes : classes.values()) {
            new ClassReader(bytes).accept(
                new ClassRemapper(new ClassWriter(0), collector),
                0);
        }
        if (!absent.isEmpty()) {
            throw new GradleException("Minimized classes reference API classes absent from the source jar: " + absent);
        }
        return references;
    }

    private static void writeJar(
        Path sourceJar,
        Path outputJar,
        Map<Type, byte[]> classes,
        Set<String> requiredResources) throws IOException {
        Files.deleteIfExists(outputJar);
        try (JarFile source = new JarFile(sourceJar.toFile());
             OutputStream fileOutput = Files.newOutputStream(outputJar, StandardOpenOption.CREATE_NEW);
             JarOutputStream output = new JarOutputStream(fileOutput)) {
            Set<String> written = new HashSet<>();
            writeManifest(output, source.getManifest(), written);

            List<Type> types = new ArrayList<>(classes.keySet());
            types.sort(Comparator.comparing(Type::getInternalName));
            for (Type type : types) {
                writeEntry(output, type.getInternalName() + ".class", classes.get(type), written);
            }
            for (String resource : requiredResources) {
                copyEntry(source, resource, output, written);
            }

            List<String> licenses = new ArrayList<>();
            source.stream()
                .filter(entry -> !entry.isDirectory())
                .map(JarEntry::getName)
                .filter(name -> name.equals("LICENSE.md") || name.startsWith("license/"))
                .forEach(licenses::add);
            Collections.sort(licenses);
            for (String license : licenses) {
                copyEntry(source, license, output, written);
            }
        }
    }

    private static void writeManifest(JarOutputStream output, Manifest source, Set<String> written) throws IOException {
        Manifest manifest = source == null ? new Manifest() : new Manifest(source);
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("GTNH-Minimized-JvmDowngrader-API", "true");
        manifest.getEntries().clear(); // Original per-entry digests no longer describe the minimized jar.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        writeEntry(output, "META-INF/MANIFEST.MF", bytes.toByteArray(), written);
    }

    private static void copyEntry(JarFile source, String name, JarOutputStream output, Set<String> written)
        throws IOException {
        JarEntry entry = source.getJarEntry(name);
        if (entry == null) {
            throw new GradleException("Required JvmDowngrader resource is absent from the source jar: " + name);
        }
        try (InputStream input = source.getInputStream(entry)) {
            writeEntry(output, name, readAllBytes(input), written);
        }
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes, Set<String> written)
        throws IOException {
        if (!written.add(name)) {
            return;
        }
        JarEntry entry = new JarEntry(name);
        entry.setTime(ZIP_EPOCH);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    public static void verifySha256(Path path, String expected) throws IOException {
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new GradleException(
                "SHA-256 mismatch for " + path.getFileName() + ": expected " + expected + ", got " + actual);
        }
    }

    public static String cacheSuffix(Path path) throws IOException {
        return "gtnh-min-" + sha256(path);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) {
            actual.append(String.format("%02x", value));
        }
        return actual.toString();
    }

    private static void writeLines(Path path, Set<String> values) throws IOException {
        Files.write(
            path,
            values,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Map<?, ?> asMap(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new GradleException("Expected " + name + " to be an object");
        }
        return (Map<?, ?>) value;
    }

    private static List<?> asList(Object value, String name) {
        if (!(value instanceof List)) {
            throw new GradleException("Expected " + name + " to be an array");
        }
        return (List<?>) value;
    }

    private static final class OutputState {
        private final Map<Type, byte[]> classes;
        private final int methods;
        private final int fields;

        private OutputState(Map<Type, byte[]> classes, int methods, int fields) {
            this.classes = classes;
            this.methods = methods;
            this.fields = fields;
        }
    }
}
