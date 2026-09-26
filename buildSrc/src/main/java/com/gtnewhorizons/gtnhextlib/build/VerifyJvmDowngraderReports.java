package com.gtnewhorizons.gtnhextlib.build;

import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.nio.file.Path;
import java.util.Map;

@DisableCachingByDefault(because = "Checks the report pair before minimization")
public abstract class VerifyJvmDowngraderReports extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJava8Report();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJava17Report();

    @TaskAction
    public void verify() {
        verifyPair(getJava8Report().get().getAsFile().toPath(), getJava17Report().get().getAsFile().toPath());
    }

    static void verifyPair(Path java8, Path java17) {
        String first = buildId(java8);
        String second = buildId(java17);
        if (!first.equals(second)) {
            throw new GradleException("DAXXL reports describe different pack builds: " + first + " and " + second);
        }
    }

    private static String buildId(Path path) {
        Object parsed = new JsonSlurper().parse(path.toFile());
        Object value = parsed instanceof Map ? ((Map<?, ?>) parsed).get("build_id") : null;
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new GradleException("Missing pack build_id in " + path);
        }
        return (String) value;
    }
}
