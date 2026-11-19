package edu.campus.recycle.service.impl;

import edu.campus.recycle.exception.ValidationException;
import edu.campus.recycle.util.Validator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 校验调用方已经存放在图片目录中的文件，数据库只保存相对路径。 */
final class ItemImagePaths {
    private ItemImagePaths() {
    }

    static List<String> validate(List<String> sources, Path workingDirectory, Path imageDirectory) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        if (sources.size() > Validator.MAX_IMAGE_COUNT) {
            throw invalid("物品最多上传 5 张图片");
        }
        Path base = workingDirectory.toAbsolutePath().normalize();
        Path root = base.resolve(imageDirectory).normalize();
        List<String> paths = new ArrayList<>(sources.size());
        for (String source : sources) {
            paths.add(validateOne(source, base, root));
        }
        return paths;
    }

    private static String validateOne(String source, Path base, Path root) {
        if (source == null || source.isBlank() || !root.startsWith(base) || root.equals(base)) {
            throw invalid("请选择图片目录中的文件");
        }
        try {
            Path relative = Path.of(source.strip().replace('\\', '/'));
            Path file = base.resolve(relative).normalize();
            if (relative.isAbsolute() || !relative.equals(relative.normalize()) || !file.startsWith(root)
                    || !Validator.isValidImageFileName(file.getFileName().toString())) {
                throw invalid("图片须为图片目录中的 jpg、jpeg 或 png 文件");
            }
            Path actualRoot = root.toRealPath();
            Path actualFile = file.toRealPath();
            if (!actualRoot.startsWith(base.toRealPath()) || !actualFile.startsWith(actualRoot)
                    || !Files.isRegularFile(actualFile) || !Validator.isValidImageSize(Files.size(actualFile))) {
                throw invalid("图片文件必须在图片目录内，且大小大于零、不超过 2 MiB");
            }
            return base.relativize(file).toString().replace('\\', '/');
        } catch (IOException | InvalidPathException e) {
            throw invalid("图片文件不存在或不可读取");
        }
    }

    private static ValidationException invalid(String message) {
        return ValidationException.ofCode("VAL-011", message);
    }
}
