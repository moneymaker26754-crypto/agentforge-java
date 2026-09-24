package io.github.moneymaker26754.agentforge.infrastructure.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceGuard {
    private final Path root;
    private final Path realRoot;

    public WorkspaceGuard(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            this.realRoot = this.root.toRealPath();
        } catch (IOException exception) {
            throw new WorkspaceViolationException("workspace does not exist: " + root);
        }
    }

    public Path resolveExisting(String relativePath) {
        Path candidate = candidate(relativePath);
        try {
            Path real = candidate.toRealPath();
            ensureInside(real);
            return real;
        } catch (IOException exception) {
            throw new WorkspaceViolationException("path does not exist: " + relativePath);
        }
    }

    public Path resolveForWrite(String relativePath) {
        Path candidate = candidate(relativePath);
        Path ancestor = candidate.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new WorkspaceViolationException("path has no existing parent: " + relativePath);
        }
        try {
            ensureInside(ancestor.toRealPath());
            return candidate;
        } catch (IOException exception) {
            throw new WorkspaceViolationException("cannot resolve path: " + relativePath);
        }
    }

    private Path candidate(String relativePath) {
        Path supplied = Path.of(relativePath);
        if (supplied.isAbsolute()) {
            throw new WorkspaceViolationException("absolute paths are not allowed");
        }
        Path candidate = root.resolve(supplied).normalize();
        ensureInside(candidate);
        return candidate;
    }

    private void ensureInside(Path candidate) {
        if (!candidate.startsWith(realRoot) && !candidate.startsWith(root)) {
            throw new WorkspaceViolationException("path escapes workspace");
        }
    }
}

