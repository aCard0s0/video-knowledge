package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the project root path and provides storage paths relative to it.
 * Looks for the project root by finding the root pom.xml file.
 */
@Getter
@Component
@Slf4j
public class ProjectPathResolver {

    private static final String ROOT_POM_MARKER = "pom.xml";
    private static final String PROJECT_ROOT_ENV = "VIDEO_KNOWLEDGE_ROOT";
    
    private final String projectRoot;
    private final String videoPath;

    public ProjectPathResolver() {
        this.projectRoot = resolveProjectRoot();
        this.videoPath = Paths.get(projectRoot, "package", "vidingest", "videos").toString();
        
        log.info("Project root resolved to: {}", projectRoot);
        log.info("Video storage path: {}", videoPath);
    }

    /**
     * Resolve the project root directory.
     * Priority:
     * 1. VIDEO_KNOWLEDGE_ROOT environment variable
     * 2. Auto-detect by finding root pom.xml
     * 3. Fallback to user.dir (current working directory)
     */
    private String resolveProjectRoot() {
        // Check environment variable first
        String envRoot = System.getenv(PROJECT_ROOT_ENV);
        if (envRoot != null && !envRoot.isEmpty()) {
            File rootDir = new File(envRoot);
            if (rootDir.exists() && rootDir.isDirectory()) {
                log.debug("Using project root from environment variable: {}", envRoot);
                return rootDir.getAbsolutePath();
            }
        }

        // Auto-detect by looking for root pom.xml
        String currentDir = System.getProperty("user.dir");

        // Look up the directory tree for pom.xml
        Path searchPath = Paths.get(currentDir).toAbsolutePath();
        int maxDepth = 10; // Prevent infinite loops
        
        while (maxDepth-- > 0) {
            File pomFile = searchPath.resolve(ROOT_POM_MARKER).toFile();
            
            // Check if this is the root pom.xml (has video-knowledge as artifactId)
            if (pomFile.exists() && isRootPom(pomFile)) {
                log.debug("Auto-detected project root: {}", searchPath);
                return searchPath.toString();
            }
            
            // Move up one level
            Path parent = searchPath.getParent();
            if (parent == null || parent.equals(searchPath)) {
                break;
            }
            searchPath = parent;
        }

        // Fallback: assume we're in the project root or use current directory
        log.warn("Could not auto-detect project root, using current directory: {}", currentDir);
        log.warn("Set VIDEO_KNOWLEDGE_ROOT environment variable to specify the project root");
        return currentDir;
    }

    /**
     * Check if a pom.xml file is the root project pom.xml
     * by checking if it contains the video-knowledge artifactId
     */
    private boolean isRootPom(File pomFile) {
        try {
            String content = new String(java.nio.file.Files.readAllBytes(pomFile.toPath()));
            // Check for root project markers
            return content.contains("<artifactId>video-knowledge</artifactId>") &&
                   content.contains("<name>Video Knowledge</name>");
        } catch (Exception e) {
            log.debug("Error reading pom.xml: {}", e.getMessage());
            return false;
        }
    }

}



