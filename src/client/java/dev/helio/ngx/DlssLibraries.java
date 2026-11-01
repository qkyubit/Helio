package dev.helio.ngx;

import dev.helio.Helio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

/**
 * The DLSS feature libraries (SR, RR, FG) NGX loads from {@code helio/ngx/}. The NVIDIA DLSS license lets
 * us fetch them from NVIDIA's own GitHub release, pinned to a tag and checked by SHA-256, instead of
 * shipping 115 MB in the jar. Players can also drop the files in by hand (any version; NGX picks the newest).
 */
public final class DlssLibraries {
	private static final String VERSION = "310.9.16";
	private static final String BASE = "https://raw.githubusercontent.com/NVIDIA/DLSS/v" + VERSION + "/lib/Linux_x86_64/rel/";

	private record Lib(String name, long size, String sha256) {}

	private static final Lib[] LIBS = {
		new Lib("libnvidia-ngx-dlss", 59481944, "30982c937afed85c96b8d313323de9a650a2eec6de1bb0f714b21750f4ce98f6"),
		new Lib("libnvidia-ngx-dlssd", 48238232, "a5d59de18841f15779039dda567a15dd723ba737915f489271fdb5f109fbfb96"),
		new Lib("libnvidia-ngx-dlssg", 7391232, "574839cae01c835de4d5845c36ca9cf75361f8160bd52397142c36a24e29e6f7"),
	};

	public static final long TOTAL_BYTES = Stream.of(LIBS).mapToLong(Lib::size).sum();

	private DlssLibraries() {}

	/** True when every library is in {@code dir}, downloaded or dropped in. */
	public static boolean present(Path dir) {
		for (Lib lib : LIBS) if (!present(dir, lib)) return false;
		return true;
	}

	private static boolean present(Path dir, Lib lib) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.anyMatch(f -> f.getFileName().toString().startsWith(lib.name + ".so."));
		} catch (IOException e) {
			return false;
		}
	}

	/** Downloads the missing libraries into {@code dir}. Blocking; reports total bytes received so far. */
	public static void download(Path dir, LongConsumer progress) throws IOException, InterruptedException {
		Files.createDirectories(dir);
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
		long done = 0;
		for (Lib lib : LIBS) {
			if (present(dir, lib)) {
				done += lib.size;
				continue;
			}
			String file = lib.name + ".so." + VERSION;
			Helio.LOG.info("Downloading {} ({} MB)", file, lib.size >> 20);
			HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(BASE + file)).build(), HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() != 200) {
				r.body().close();
				throw new IOException(file + ": HTTP " + r.statusCode());
			}
			Path part = dir.resolve(file + ".part");
			MessageDigest sha = sha256();
			long got = 0;
			try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
				byte[] buf = new byte[1 << 16];
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n);
					sha.update(buf, 0, n);
					got += n;
					progress.accept(done + got);
				}
			}
			String hash = HexFormat.of().formatHex(sha.digest());
			if (got != lib.size || !hash.equals(lib.sha256)) {
				Files.deleteIfExists(part);
				throw new IOException(file + ": checksum mismatch (got " + got + " bytes, sha256 " + hash + ")");
			}
			Files.move(part, dir.resolve(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			done += lib.size;
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
