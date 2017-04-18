/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.changedetection.resources.Snapshottable;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.internal.file.collections.SimpleFileCollection;
import org.gradle.api.internal.hash.FileHasher;
import org.gradle.cache.internal.DefaultProducerGuard;
import org.gradle.cache.internal.ProducerGuard;
import org.gradle.caching.internal.BuildCacheHasher;
import org.gradle.internal.Factory;
import org.gradle.internal.nativeintegration.filesystem.FileMetadataSnapshot;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

/**
 * Responsible for snapshotting various aspects of the file system.
 *
 * Currently logic and state are split between this class and {@link FileSystemMirror}, as there are several instances of this class created in different scopes. This introduces some inefficiencies that could be improved by shuffling this relationship around.
 *
 * The implementations attempt to do 2 things: avoid doing the same work in parallel (e.g. scanning the same directory from multiple threads, and avoid doing work where the result is almost certainly the same as before (e.g. don't scan the output directory of a task a bunch of times).
 *
 * The implementations are currently intentionally very, very simple, and so there are a number of ways in which they can be made much more efficient. This can happen over time.
 */
public class DefaultFileSystemSnapshotter implements FileSystemSnapshotter {
    private final FileHasher hasher;
    private final StringInterner stringInterner;
    private final FileSystem fileSystem;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final FileSystemMirror fileSystemMirror;
    private final ProducerGuard<String> producingSelfSnapshots = new DefaultProducerGuard<String>();
    private final ProducerGuard<String> producingTrees = new DefaultProducerGuard<String>();
    private final ProducerGuard<String> producingAllSnapshots = new DefaultProducerGuard<String>();
    private final DefaultGenericFileCollectionSnapshotter snapshotter;

    public DefaultFileSystemSnapshotter(FileHasher hasher, StringInterner stringInterner, FileSystem fileSystem, DirectoryFileTreeFactory directoryFileTreeFactory, FileSystemMirror fileSystemMirror) {
        this.hasher = hasher;
        this.stringInterner = stringInterner;
        this.fileSystem = fileSystem;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.fileSystemMirror = fileSystemMirror;
        this.snapshotter = new DefaultGenericFileCollectionSnapshotter(this, stringInterner);
    }

    @Override
    public SnapshottableFileSystemResource snapshotSelf(final File file) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = file.getAbsolutePath();
        return producingSelfSnapshots.guardByKey(path, new Factory<SnapshottableFileSystemResource>() {
            @Override
            public SnapshottableFileSystemResource create() {
                SnapshottableFileSystemResource fileSystemResource = fileSystemMirror.getFile(path);
                if (fileSystemResource == null) {
                    fileSystemResource = calculateDetails(file);
                    fileSystemMirror.putFile(fileSystemResource);
                }
                return fileSystemResource;
            }
        });
    }

    @Override
    public Snapshot snapshotAll(final File file) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = file.getAbsolutePath();
        return producingAllSnapshots.guardByKey(path, new Factory<Snapshot>() {
            @Override
            public Snapshot create() {
                Snapshot snapshot = fileSystemMirror.getContent(path);
                if (snapshot == null) {
                    FileCollectionSnapshot fileCollectionSnapshot = snapshotter.snapshot(new SimpleFileCollection(file), TaskFilePropertyCompareStrategy.UNORDERED, TaskFilePropertySnapshotNormalizationStrategy.ABSOLUTE);
                    HashCode hashCode = fileCollectionSnapshot.getHash();
                    snapshot = new HashBackedSnapshot(hashCode);
                    String internedPath = getPath(file);
                    fileSystemMirror.putContent(internedPath, snapshot);
                }
                return snapshot;
            }
        });
    }

    @Override
    public SnapshottableDirectoryTree snapshotDirectoryTree(final File dir) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        final String path = dir.getAbsolutePath();
        return producingTrees.guardByKey(path, new Factory<SnapshottableDirectoryTree>() {
            @Override
            public SnapshottableDirectoryTree create() {
                SnapshottableDirectoryTree directoryTree = fileSystemMirror.getDirectoryTree(path);
                if (directoryTree == null) {
                    // Scan the directory
                    directoryTree = doSnapshot(directoryFileTreeFactory.create(dir));
                    fileSystemMirror.putDirectory(directoryTree);
                }
                return directoryTree;
            }
        });
    }

    @Override
    public SnapshottableDirectoryTree snapshotDirectoryTree(final DirectoryFileTree dirTree) {
        // Could potentially coordinate with a thread that is snapshotting an overlapping directory tree
        // Currently cache only those trees where we want everything from a directory
        if (!dirTree.getPatterns().isEmpty()) {
            List<SnapshottableFileSystemResource> elements = Lists.newArrayList();
            dirTree.visit(new FileVisitorImpl(elements));
            return new DefaultSnapshottableDirectoryTree(dirTree.getDir().getAbsolutePath(), elements);
        }

        final String path = dirTree.getDir().getAbsolutePath();
        return producingTrees.guardByKey(path, new Factory<SnapshottableDirectoryTree>() {
            @Override
            public SnapshottableDirectoryTree create() {
                SnapshottableDirectoryTree directoryTree = fileSystemMirror.getDirectoryTree(path);
                if (directoryTree == null) {
                    // Scan the directory
                    directoryTree = doSnapshot(dirTree);
                    fileSystemMirror.putDirectory(directoryTree);
                }
                return directoryTree;
            }
        });
    }

    @Override
    public SnapshottableResourceTree snapshotTree(FileTreeInternal tree) {
        List<SnapshottableFileSystemResource> elements = Lists.newArrayList();
        tree.visitTreeOrBackingFile(new FileVisitorImpl(elements));
        return new DefaultSnapshottableResourceTree(null, elements);
    }

    @Override
    public List<Snapshottable> fileCollection(FileCollection input) {
        LinkedList<Snapshottable> fileTreeElements = Lists.newLinkedList();
        FileCollectionInternal fileCollection = (FileCollectionInternal) input;
        FileCollectionVisitorImpl visitor = new FileCollectionVisitorImpl(fileTreeElements);
        fileCollection.visitRootElements(visitor);
        return fileTreeElements;
    }

    private SnapshottableDirectoryTree doSnapshot(DirectoryFileTree directoryTree) {
        String path = getPath(directoryTree.getDir());
        List<SnapshottableFileSystemResource> elements = Lists.newArrayList();
        directoryTree.visit(new FileVisitorImpl(elements));
        return new DefaultSnapshottableDirectoryTree(path, ImmutableList.copyOf(elements));
    }

    private String getPath(File file) {
        return stringInterner.intern(file.getAbsolutePath());
    }

    private SnapshottableFileSystemResource calculateDetails(File file) {
        String path = getPath(file);
        FileMetadataSnapshot stat = fileSystem.stat(file);
        switch (stat.getType()) {
            case Missing:
                return new SnapshottableMissingFileSystemFile(path, file.getName());
            case Directory:
                return new SnapshottableFileSystemDirectory(path, new RelativePath(false, file.getName()), true);
            case RegularFile:
                return new SnapshottableFileSystemFile(path, new RelativePath(true, file.getName()), true, fileSnapshot(file, stat));
            default:
                throw new IllegalArgumentException("Unrecognized file type: " + stat.getType());
        }
    }

    private FileHashSnapshot fileSnapshot(FileTreeElement fileDetails) {
        return new FileHashSnapshot(hasher.hash(fileDetails), fileDetails.getLastModified());
    }

    private FileHashSnapshot fileSnapshot(File file, FileMetadataSnapshot fileDetails) {
        return new FileHashSnapshot(hasher.hash(file, fileDetails), fileDetails.getLastModified());
    }

    private static class HashBackedSnapshot implements Snapshot {
        private final HashCode hashCode;

        HashBackedSnapshot(HashCode hashCode) {
            this.hashCode = hashCode;
        }

        @Override
        public void appendToHasher(BuildCacheHasher hasher) {
            hasher.putBytes(hashCode.asBytes());
        }
    }

    private class FileVisitorImpl implements FileVisitor {
        private final List<SnapshottableFileSystemResource> fileSystemResources;

        FileVisitorImpl(List<SnapshottableFileSystemResource> fileSystemResources) {
            this.fileSystemResources = fileSystemResources;
        }

        @Override
        public void visitDir(FileVisitDetails dirDetails) {
            fileSystemResources.add(new SnapshottableFileSystemDirectory(getPath(dirDetails.getFile()), dirDetails.getRelativePath(), false));
        }

        @Override
        public void visitFile(FileVisitDetails fileDetails) {
            fileSystemResources.add(new SnapshottableFileSystemFile(getPath(fileDetails.getFile()), fileDetails.getRelativePath(), false, fileSnapshot(fileDetails)));
        }
    }

    private class FileCollectionVisitorImpl implements FileCollectionVisitor {
        private final List<Snapshottable> fileTreeElements;

        FileCollectionVisitorImpl(List<Snapshottable> fileTreeElements) {
            this.fileTreeElements = fileTreeElements;
        }

        @Override
        public void visitCollection(FileCollectionInternal fileCollection) {
            for (File file : fileCollection) {
                SnapshottableFileSystemResource fileSystemResource = snapshotSelf(file);
                switch (fileSystemResource.getType()) {
                    case Missing:
                        fileTreeElements.add(fileSystemResource);
                        break;
                    case RegularFile:
                        fileTreeElements.add(fileSystemResource);
                        break;
                    case Directory:
                        // Visit the directory itself, then its contents
                        fileTreeElements.add(fileSystemResource);
                        visitDirectoryTree(directoryFileTreeFactory.create(file));
                        break;
                    default:
                        throw new AssertionError();
                }
            }
        }

        @Override
        public void visitTree(FileTreeInternal fileTree) {
            fileTreeElements.add(snapshotTree(fileTree));
        }

        @Override
        public void visitDirectoryTree(DirectoryFileTree directoryTree) {
            fileTreeElements.add(snapshotDirectoryTree(directoryTree));
        }
    }
}
