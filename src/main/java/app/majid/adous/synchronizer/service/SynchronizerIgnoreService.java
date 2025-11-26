package app.majid.adous.synchronizer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SynchronizerIgnoreService {
    private final List<PathMatcher> ignoreMatchers = new ArrayList<>();

    public SynchronizerIgnoreService(@Value("${github.sync-ignore-file:}") String ignoreFilePath)
            throws IOException {

        List<String> ignorePatterns = Collections.emptyList();
        if (ignoreFilePath != null && !ignoreFilePath.isBlank()) {
            ignorePatterns = Files.readAllLines(Paths.get(ignoreFilePath));
        } else {
            var resource = new ClassPathResource(".syncignore");
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream()))) {
                    ignorePatterns = reader.lines().collect(Collectors.toList());
                }
            }
        }

        loadIgnorePatterns(ignorePatterns);
    }

    private void loadIgnorePatterns(List<String> ignorePatterns) {
        for (String ignorePattern : ignorePatterns) {
            String trimmed = ignorePattern.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                ignoreMatchers.add(FileSystems.getDefault()
                        .getPathMatcher("glob:" + trimmed));
            }
        }
    }

    public boolean shouldProcess(String filePath) {
        return ignoreMatchers.stream()
                .noneMatch(matcher ->
                        matcher.matches(FileSystems.getDefault().getPath(filePath)));
    }
}
