package com.gtnewhorizons.gtnhextlib.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@DisableCachingByDefault(because = "Artifact checksums must be verified on every build")
public abstract class VerifyJvmDowngraderArtifacts extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getArtifacts();

    @Input
    public abstract MapProperty<String, String> getChecksums();

    @TaskAction
    public void verify() throws Exception {
        Map<String, File> artifactsByName = new HashMap<>();
        for (File artifact : getArtifacts()) {
            artifactsByName.put(artifact.getName(), artifact);
        }
        for (Map.Entry<String, String> checksum : getChecksums().get().entrySet()) {
            File artifact = artifactsByName.get(checksum.getKey());
            if (artifact == null) {
                throw new GradleException("Expected JvmDowngrader artifact is absent: " + checksum.getKey());
            }
            MinimizeJvmDowngraderApi.verifySha256(artifact.toPath(), checksum.getValue());
        }
    }
}
