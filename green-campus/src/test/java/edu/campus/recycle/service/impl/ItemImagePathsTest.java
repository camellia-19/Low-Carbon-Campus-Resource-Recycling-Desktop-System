package edu.campus.recycle.service.impl;

import edu.campus.recycle.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemImagePathsTest {
    private static final Path IMAGE_DIR = Path.of("data", "images");
    @TempDir Path workspace;

    @Test
    void returnsProjectRelativePathForStagedFileAtSizeLimit() throws IOException {
        Path root = Files.createDirectories(workspace.resolve(IMAGE_DIR));
        Files.write(root.resolve("book.png"), new byte[2 * 1024 * 1024]);
        assertEquals(List.of("data/images/book.png"),
                ItemImagePaths.validate(List.of("data/images/book.png"), workspace, IMAGE_DIR));
    }

    @Test
    void rejectsImageOutsideStorageDirectoryAndMissingOrOversizeFiles() throws IOException {
        Path root = Files.createDirectories(workspace.resolve(IMAGE_DIR));
        Files.write(root.resolve("large.png"), new byte[2 * 1024 * 1024 + 1]);
        Files.write(workspace.resolve("outside.png"), new byte[1]);
        for (String invalid : List.of("outside.png", root.resolve("large.png").toString(),
                "data/images/large.png", "data/images/missing.png")) {
            assertEquals("VAL-011", assertThrows(ValidationException.class,
                    () -> ItemImagePaths.validate(List.of(invalid), workspace, IMAGE_DIR)).getCode());
        }
    }
}
