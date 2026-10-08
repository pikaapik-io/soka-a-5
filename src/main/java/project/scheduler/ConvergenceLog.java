package project.scheduler;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Best fitness every {@code every} evaluations, plus the final evaluation. */
public final class ConvergenceLog {
	public static final int DEFAULT_EVERY = 100;

	public static final class Point {
		public final long evaluation;
		public final double bestFitness;

		Point(long evaluation, double bestFitness) {
			this.evaluation = evaluation;
			this.bestFitness = bestFitness;
		}
	}

	private final int every;
	private final List<Point> points = new ArrayList<>();

	public ConvergenceLog(int every) {
		if (every < 1) {
			throw new IllegalArgumentException("every must be at least 1");
		}
		this.every = every;
	}

	void record(long evaluation, double bestFitness) {
		if (evaluation % every == 0) {
			points.add(new Point(evaluation, bestFitness));
		}
	}

	void finish(long evaluation, double bestFitness) {
		if (points.isEmpty() || points.get(points.size() - 1).evaluation != evaluation) {
			points.add(new Point(evaluation, bestFitness));
		}
	}

	public List<Point> points() {
		return Collections.unmodifiableList(points);
	}

	public void writeCsv(Path path) throws IOException {
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
			out.println("evaluation,best_fitness");
			for (Point p : points) {
				out.printf(Locale.US, "%d,%.8f%n", p.evaluation, p.bestFitness);
			}
		}
	}
}
