package com.tradinglabs.vidingest.download.util;

import com.tradinglabs.vidingest.core.download.util.FileSystemHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemHelperTest {

    @Test
    void sanitizeFilename_removesInvalidCharacters() {
        assertEquals("hello_world", FileSystemHelper.sanitizeFilename("hello<world>"));
        assertEquals("file_name", FileSystemHelper.sanitizeFilename("file:name"));
        assertEquals("path_to_file", FileSystemHelper.sanitizeFilename("path/to\\file"));
    }

    @Test
    void sanitizeFilename_handlesNull() {
        assertEquals("video", FileSystemHelper.sanitizeFilename(null));
    }

    @Test
    void sanitizeFilename_handlesEmptyString() {
        assertEquals("video", FileSystemHelper.sanitizeFilename(""));
    }

    @Test
    void sanitizeFilename_collapsesMultipleSpaces() {
        assertEquals("hello world", FileSystemHelper.sanitizeFilename("hello   world"));
    }

    @Test
    void sanitizeFilename_truncatesLongNames() {
        String longName = "a".repeat(300);
        String sanitized = FileSystemHelper.sanitizeFilename(longName);
        assertTrue(sanitized.length() <= 200);
    }

    @Test
    void sanitizeFilename_removesTrailingDots() {
        assertEquals("filename", FileSystemHelper.sanitizeFilename("filename..."));
    }

    @Test
    void sanitizeFilename_removesControlCharacters() {
        assertEquals("cleantext", FileSystemHelper.sanitizeFilename("clean\u0000text"));
    }

    @Test
    void getFileExtension_extractsMp4() {
        assertEquals(".mp4", FileSystemHelper.getFileExtension("video.mp4"));
    }

    @Test
    void getFileExtension_extractsCompoundExtension() {
        assertEquals(".json", FileSystemHelper.getFileExtension("metadata.info.json"));
    }

    @Test
    void getFileExtension_returnsEmptyForNoExtension() {
        assertEquals("", FileSystemHelper.getFileExtension("noextension"));
    }

    @Test
    void getFileExtension_returnsEmptyForDotAtStart() {
        assertEquals("", FileSystemHelper.getFileExtension(".hidden"));
    }

    @Test
    void ensureDirectoriesExist_createsNewDirectories(@TempDir Path tempDir) throws IOException {
        Path newDir = tempDir.resolve("sub/nested");
        FileSystemHelper.ensureDirectoriesExist(newDir.toString());
        assertTrue(Files.isDirectory(newDir));
    }

    @Test
    void ensureDirectoriesExist_succeedsForExistingDirectory(@TempDir Path tempDir) throws IOException {
        FileSystemHelper.ensureDirectoriesExist(tempDir.toString());
        assertTrue(Files.isDirectory(tempDir));
    }

    @Test
    void createAndVerifyDirectory_createsAndReturnsPath(@TempDir Path tempDir) throws IOException {
        Path newDir = tempDir.resolve("channel");
        Path result = FileSystemHelper.createAndVerifyDirectory(newDir.toString());
        assertNotNull(result);
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void findFileByPrefix_findsMatchingFile(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("abc123.mp4"));
        String found = FileSystemHelper.findFileByPrefix(tempDir, "abc123");
        assertTrue(found.endsWith("abc123.mp4"));
    }

    @Test
    void findFileByPrefix_throwsWhenNoMatch(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("other.mp4"));
        assertThrows(IOException.class,
                () -> FileSystemHelper.findFileByPrefix(tempDir, "missing"));
    }

    @Test
    void findFileByPrefix_throwsForNonexistentDirectory() {
        assertThrows(IOException.class,
                () -> FileSystemHelper.findFileByPrefix(Path.of("/nonexistent/path"), "file"));
    }

    @Test
    void findLatestVideoFile_findsVideoFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("video.mp4"));
        Files.createFile(tempDir.resolve("metadata.json"));
        Path result = FileSystemHelper.findLatestVideoFile(tempDir);
        assertEquals("video.mp4", result.getFileName().toString());
    }

    @Test
    void findLatestVideoFile_throwsWhenOnlyNonVideoFiles(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("data.json"));
        Files.createFile(tempDir.resolve("readme.txt"));
        assertThrows(IOException.class,
                () -> FileSystemHelper.findLatestVideoFile(tempDir));
    }

    @Test
    void findLatestVideoFile_throwsForEmptyDirectory(@TempDir Path tempDir) {
        assertThrows(IOException.class,
                () -> FileSystemHelper.findLatestVideoFile(tempDir));
    }
}
