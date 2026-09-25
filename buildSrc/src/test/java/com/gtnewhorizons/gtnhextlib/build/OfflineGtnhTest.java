package com.gtnewhorizons.gtnhextlib.build;

import org.gradle.api.GradleException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

public final class OfflineGtnhTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createDirectories(Paths.get(args[0]));
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        Files.write(first, new byte[] {1, 2, 3});
        Files.write(second, new byte[] {1, 2, 3});
        String suffix = MinimizeJvmDowngraderApi.cacheSuffix(first);
        check(suffix.matches("gtnh-min-[0-9a-f]{64}"), "Cache suffix must contain the full SHA-256");
        check(suffix.equals(MinimizeJvmDowngraderApi.cacheSuffix(second)), "Equal content must share an identity");
        Files.write(second, new byte[] {1, 2, 4});
        check(!suffix.equals(MinimizeJvmDowngraderApi.cacheSuffix(second)), "Changed content needs a new identity");

        Path java8 = directory.resolve("java8.json");
        Path java17 = directory.resolve("java17.json");
        Files.write(java8, "{\"build_id\":\"daily-750\"}".getBytes(StandardCharsets.UTF_8));
        Files.copy(java8, java17, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        VerifyJvmDowngraderReports.verifyPair(java8, java17);
        for (String invalid : new String[] {"{}", "{\"build_id\":\" \"}", "{\"build_id\":\"daily-751\"}"}) {
            Files.write(java17, invalid.getBytes(StandardCharsets.UTF_8));
            try {
                VerifyJvmDowngraderReports.verifyPair(java8, java17);
                throw new AssertionError("Invalid report pair accepted: " + invalid);
            } catch (GradleException expected) {
                // Expected: incomplete or mismatched reports cannot produce a combined artifact.
            }
        }

        String support = "xyz/wagyourtail/jvmdg/example/Support";
        Type consumer = Type.getObjectType("example/Consumer");
        Type dependency = Type.getObjectType(support);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, consumer.getInternalName(), null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, support);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        Set<Type> references = MinimizeJvmDowngraderApi.internalReferences(
            Collections.singletonMap(consumer, writer.toByteArray()), Collections.singletonMap(support, dependency));
        check(references.contains(dependency), "References appearing only in method bodies must be scanned");
        try {
            MinimizeJvmDowngraderApi.internalReferences(
                Collections.singletonMap(consumer, writer.toByteArray()), Collections.emptyMap());
            throw new AssertionError("Missing method-body dependency accepted");
        } catch (GradleException expected) {
            // Expected: references to unavailable API classes must fail.
        }
        System.out.println("Offline GTNH checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
