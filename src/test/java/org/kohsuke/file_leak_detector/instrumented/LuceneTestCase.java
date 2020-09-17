package org.kohsuke.file_leak_detector.instrumented;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.ActivityListener;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.FileRecord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LuceneTestCase {
	private static final StringWriter output = new StringWriter();
	private Path tempDir;
	private Object obj;
	private FileSystem fileSystem;

	private final ActivityListener listener = new ActivityListener() {
		@Override
		public void open(Object obj, File file) {
			LuceneTestCase.this.obj = obj;
		}

		@Override
		public void openSocket(Object obj) {
			LuceneTestCase.this.obj = obj;
		}

		@Override
		public void close(Object obj) {
			LuceneTestCase.this.obj = obj;
		}

		@Override
		public void fd_open(Object obj) {
			LuceneTestCase.this.obj = obj;
		}
	};

	@BeforeClass
	public static void setup() {
		assertTrue("This test expects the Java Agent to be installed via commandline options",
				Listener.isAgentInstalled());
		Listener.TRACE = new PrintWriter(output);
	}

	@Before
	public void registerListener() {
		ActivityListener.LIST.add(listener);
	}

	@After
	public void unregisterListener() {
		ActivityListener.LIST.remove(listener);
	}

	@Before
	public void prepareOutput() throws Exception {
		FileSystem fs = FileSystems.getDefault();
		fs = new FilterFileSystemProvider("extras://", fs) {}.getFileSystem(null);
		fileSystem = fs.provider().getFileSystem(URI.create("file:///"));

		tempDir = fileSystem.getPath(System.getProperty("java.io.tmpdir"));

		Files.createDirectories(tempDir);
	}

	@Test
	public void testConstants() throws Throwable {
		DirectoryStream<Path> directoryStream = Files.newDirectoryStream(tempDir);

		assertNotNull("No file record for file=" + tempDir + " found", findFileRecord(tempDir.toFile()));

		assertTrue("Did not have the expected type of 'marker' object: " + obj,
				obj instanceof DirectoryStream);

		directoryStream.close();

		assertNull("File record for file=" + tempDir + " not removed", findFileRecord(tempDir.toFile()));

		fileSystem.close();

		String traceOutput = output.toString();
		assertTrue(traceOutput.contains("Opened " + tempDir));
		assertTrue(traceOutput.contains("Closed " + tempDir));
	}

	public abstract class FilterFileSystemProvider extends FileSystemProvider {
		protected final FileSystemProvider delegate;
		protected FileSystem fileSystem;
		protected final String scheme;

		public FilterFileSystemProvider(String scheme, FileSystem delegateInstance) {
			this.scheme = Objects.requireNonNull(scheme);
			Objects.requireNonNull(delegateInstance);
			this.delegate = delegateInstance.provider();
			this.fileSystem = new FilterFileSystem(this, delegateInstance);
		}

		@Override
		public String getScheme() {
			return scheme;
		}

		@Override
		public FileSystem newFileSystem(URI uri, Map<String,?> env) {
			if (fileSystem == null) {
				throw new IllegalStateException("subclass did not initialize singleton filesystem");
			}
			return fileSystem;
		}

		@Override
		public FileSystem newFileSystem(Path path, Map<String,?> env) {
			if (fileSystem == null) {
				throw new IllegalStateException("subclass did not initialize singleton filesystem");
			}
			return fileSystem;
		}

		@Override
		public FileSystem getFileSystem(URI uri) {
			if (fileSystem == null) {
				throw new IllegalStateException("subclass did not initialize singleton filesystem");
			}
			return fileSystem;
		}


		@Override
		public Path getPath( URI uri) {
			if (fileSystem == null) {
				throw new IllegalStateException("subclass did not initialize singleton filesystem");
			}
			Path path = delegate.getPath(uri);
			return new FilterPath(path, fileSystem);
		}

		@Override
		public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
			delegate.createDirectory(toDelegate(dir), attrs);
		}

		@Override
		public void delete(Path path) throws IOException {
			delegate.delete(toDelegate(path));
		}

		@Override
		public void copy(Path source, Path target, CopyOption... options) throws IOException {
			delegate.copy(toDelegate(source), toDelegate(target), options);
		}

		@Override
		public void move(Path source, Path target, CopyOption... options) throws IOException {
			delegate.move(toDelegate(source), toDelegate(target), options);
		}

		@Override
		public boolean isSameFile(Path path, Path path2) throws IOException {
			return delegate.isSameFile(toDelegate(path), toDelegate(path2));
		}

		@Override
		public boolean isHidden(Path path) throws IOException {
			return delegate.isHidden(toDelegate(path));
		}

		@Override
		public FileStore getFileStore(Path path) throws IOException {
			return delegate.getFileStore(toDelegate(path));
		}

		@Override
		public void checkAccess(Path path, AccessMode... modes) throws IOException {
			delegate.checkAccess(toDelegate(path), modes);
		}

		@Override
		public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
			return delegate.getFileAttributeView(toDelegate(path), type, options);
		}

		@Override
		public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
			return delegate.readAttributes(toDelegate(path), type, options);
		}

		@Override
		public Map<String,Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
			return delegate.readAttributes(toDelegate(path), attributes, options);
		}

		@Override
		public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
			delegate.setAttribute(toDelegate(path), attribute, value, options);
		}

		@Override
		public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
			return delegate.newInputStream(toDelegate(path), options);
		}

		@Override
		public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
			return delegate.newOutputStream(toDelegate(path), options);
		}

		@Override
		public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
			return delegate.newFileChannel(toDelegate(path), options, attrs);
		}

		@Override
		public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options, ExecutorService executor, FileAttribute<?>... attrs) throws IOException {
			return delegate.newAsynchronousFileChannel(toDelegate(path), options, executor, attrs);
		}

		@Override
		public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
			return delegate.newByteChannel(toDelegate(path), options, attrs);
		}

		@Override
		public DirectoryStream<Path> newDirectoryStream(Path dir, final Filter<? super Path> filter) throws IOException {
			return new FilterDirectoryStream(delegate.newDirectoryStream(toDelegate(dir), filter), fileSystem);
		}

		@Override
		public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
			delegate.createSymbolicLink(toDelegate(link), toDelegate(target), attrs);
		}

		@Override
		public void createLink(Path link, Path existing) throws IOException {
			delegate.createLink(toDelegate(link), toDelegate(existing));
		}

		@Override
		public boolean deleteIfExists(Path path) throws IOException {
			return delegate.deleteIfExists(toDelegate(path));
		}

		@Override
		public Path readSymbolicLink(Path link) throws IOException {
			return delegate.readSymbolicLink(toDelegate(link));
		}

		protected Path toDelegate(Path path) {
			if (path instanceof FilterPath) {
				FilterPath fp = (FilterPath) path;
				if (fp.fileSystem != fileSystem) {
					throw new ProviderMismatchException("mismatch, expected: " + fileSystem.provider().getClass() + ", got: " + fp.fileSystem.provider().getClass());
				}
				return fp.delegate;
			} else {
				throw new ProviderMismatchException("mismatch, expected: FilterPath, got: " + path.getClass());
			}
		}

		/**
		 * Override to trigger some behavior when the filesystem is closed.
		 * <p>
		 * This is always called for each FilterFileSystemProvider in the chain.
		 */
		protected void onClose() {
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "(" + delegate + ")";
		}
	}

	public class FilterFileSystem extends FileSystem {

		protected final FilterFileSystemProvider parent;

		protected final FileSystem delegate;

		public FilterFileSystem(FilterFileSystemProvider parent, FileSystem delegate) {
			this.parent = Objects.requireNonNull(parent);
			this.delegate = Objects.requireNonNull(delegate);
		}

		@Override
		public FileSystemProvider provider() {
			return parent;
		}

		@Override
		public void close() throws IOException {
			if (delegate == FileSystems.getDefault()) {
				// you can't close the default provider!
				parent.onClose();
			} else {
				//noinspection unused
				try (FileSystem d = delegate) {
					parent.onClose();
				}
			}
		}

		@Override
		public boolean isOpen() {
			return delegate.isOpen();
		}

		@Override
		public boolean isReadOnly() {
			return delegate.isReadOnly();
		}

		@Override
		public String getSeparator() {
			return delegate.getSeparator();
		}

		@Override
		public Iterable<Path> getRootDirectories() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Iterable<FileStore> getFileStores() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<String> supportedFileAttributeViews() {
			return delegate.supportedFileAttributeViews();
		}


		@Override
		public Path getPath( String first,  String... more) {
			return new FilterPath(delegate.getPath(first, more), this);
		}

		@Override
		public PathMatcher getPathMatcher(String syntaxAndPattern) {
			final PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
			return path -> {
				if (path instanceof FilterPath) {
					return matcher.matches(((FilterPath)path).delegate);
				}
				return false;
			};
		}

		@Override
		public UserPrincipalLookupService getUserPrincipalLookupService() {
			return delegate.getUserPrincipalLookupService();
		}

		@Override
		public WatchService newWatchService() throws IOException {
			return delegate.newWatchService();
		}
	}

	public static class FilterPath implements Path {

		@Override
		public void forEach(Consumer<? super Path> action) {

		}

		protected final Path delegate;

		protected final FileSystem fileSystem;

		public FilterPath(Path delegate, FileSystem fileSystem) {
			this.delegate = delegate;
			this.fileSystem = fileSystem;
		}


		@Override
		public FileSystem getFileSystem() {
			return fileSystem;
		}

		@Override
		public boolean isAbsolute() {
			return delegate.isAbsolute();
		}

		@Override
		public Path getRoot() {
			Path root = delegate.getRoot();
			if (root == null) {
				return null;
			}
			return wrap(root);
		}

		@Override
		public Path getFileName() {
			Path fileName = delegate.getFileName();
			if (fileName == null) {
				return null;
			}
			return wrap(fileName);
		}

		@Override
		public Path getParent() {
			Path parent = delegate.getParent();
			if (parent == null) {
				return null;
			}
			return wrap(parent);
		}

		@Override
		public int getNameCount() {
			return delegate.getNameCount();
		}


		@Override
		public Path getName(int index) {
			return wrap(delegate.getName(index));
		}


		@Override
		public Path subpath(int beginIndex, int endIndex) {
			return wrap(delegate.subpath(beginIndex, endIndex));
		}

		@Override
		public boolean startsWith( Path other) {
			return delegate.startsWith(toDelegate(other));
		}

		@Override
		public boolean startsWith( String other) {
			return delegate.startsWith(other);
		}

		@Override
		public boolean endsWith( Path other) {
			return delegate.endsWith(toDelegate(other));
		}

		@Override
		public boolean endsWith( String other) {
			return delegate.startsWith(other);
		}

		@Override
		public Path normalize() {
			return wrap(delegate.normalize());
		}


		@Override
		public Path resolve( Path other) {
			return wrap(delegate.resolve(toDelegate(other)));
		}


		@Override
		public Path resolve( String other) {
			return wrap(delegate.resolve(other));
		}


		@Override
		public Path resolveSibling( Path other) {
			return wrap(delegate.resolveSibling(toDelegate(other)));
		}


		@Override
		public Path resolveSibling( String other) {
			return wrap(delegate.resolveSibling(other));
		}


		@Override
		public Path relativize( Path other) {
			return wrap(delegate.relativize(toDelegate(other)));
		}

		@Override
		public URI toUri() {
			return delegate.toUri();
		}

		@Override
		public String toString() {
			return delegate.toString();
		}


		@Override
		public Path toAbsolutePath() {
			return wrap(delegate.toAbsolutePath());
		}


		@Override
		public Path toRealPath( LinkOption... options) throws IOException {
			return wrap(delegate.toRealPath(options));
		}


		@Override
		public File toFile() {
			return delegate.toFile();
		}

		@Override
		public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
			return delegate.register(watcher, events, modifiers);
		}

		@Override
		public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
			return delegate.register(watcher, events);
		}


		@Override
		public Iterator<Path> iterator() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int compareTo(Path other) {
			return delegate.compareTo(toDelegate(other));
		}

		@Override
		public int hashCode() {
			return delegate.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			FilterPath other = (FilterPath) obj;
			if (delegate == null) {
				if (other.delegate != null) return false;
			} else if (!delegate.equals(other.delegate)) return false;
			if (fileSystem == null) {
				return other.fileSystem == null;
			} else
				return fileSystem.equals(other.fileSystem);
		}

		protected Path wrap(Path other) {
			return new FilterPath(other, fileSystem);
		}

		protected Path toDelegate(Path path) {
			if (path instanceof FilterPath) {
				FilterPath fp = (FilterPath) path;
				if (fp.fileSystem != fileSystem) {
					throw new ProviderMismatchException("mismatch, expected: " + fileSystem.provider().getClass() + ", got: " + fp.fileSystem.provider().getClass());
				}
				return fp.delegate;
			} else {
				throw new ProviderMismatchException("mismatch, expected: FilterPath, got: " + path.getClass());
			}
		}
	}

	public static class FilterDirectoryStream implements DirectoryStream<Path> {

		protected final DirectoryStream<Path> delegate;

		protected final FileSystem fileSystem;

		public FilterDirectoryStream(DirectoryStream<Path> delegate, FileSystem fileSystem) {
			this.delegate = Objects.requireNonNull(delegate);
			this.fileSystem = Objects.requireNonNull(fileSystem);
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}

		@Override
		public Iterator<Path> iterator() {
			throw new UnsupportedOperationException();
		}
	}

	private static FileRecord findFileRecord(File file) {
		for (Listener.Record record : Listener.getCurrentOpenFiles()) {
			if (record instanceof FileRecord) {
				FileRecord fileRecord = (FileRecord) record;
				if (fileRecord.file == file || fileRecord.file.getName().equals(file.getName())) {
					return fileRecord;
				}
			}
		}
		return null;
	}
}
