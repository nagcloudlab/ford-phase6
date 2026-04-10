package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class BarServiceApplication {

	// DATA_DIR env var controls where we persist state.
	// Default /data — mount a volume here (emptyDir / hostPath / GCS PVC).
	@Value("${DATA_DIR:/data}")
	private String dataDir;

	private Path notesFile;

	@PostConstruct
	public void init() throws IOException {
		Path dir = Paths.get(dataDir);
		Files.createDirectories(dir);
		this.notesFile = dir.resolve("notes.log");
		if (!Files.exists(notesFile)) {
			Files.createFile(notesFile);
		}
		System.out.println("bar-service state dir = " + dir.toAbsolutePath());
	}

	@GetMapping("/bar")
	public String bar() {
		String host = "unknown";
		try {
			host = java.net.InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return "Hello from Bar Service running on- " + host + " (stateDir=" + dataDir + ")";
	}

	@PostMapping("/bar/notes")
	public String addNote(@RequestParam("msg") String msg) throws IOException {
		String host = java.net.InetAddress.getLocalHost().getHostName();
		String line = Instant.now() + " [" + host + "] " + msg + System.lineSeparator();
		Files.write(notesFile, line.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		return "saved: " + line;
	}

	@GetMapping("/bar/notes")
	public List<String> getNotes() throws IOException {
		if (!Files.exists(notesFile)) {
			return Collections.emptyList();
		}
		return Files.readAllLines(notesFile, StandardCharsets.UTF_8);
	}

	public static void main(String[] args) {
		SpringApplication.run(BarServiceApplication.class, args);
	}

}
