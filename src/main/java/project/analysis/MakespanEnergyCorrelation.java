package project.analysis;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import project.data.GoCJLoader;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;

/**
 * Analisis wajib Fase 3: 1000 mapping acak pada sebuah dataset, untuk mengecek
 * apakah makespan dan energi benar-benar tujuan (objective) yang berbeda
 * (korelasi Pearson) dan seberapa besar komponen energi dinamis dibanding total
 * energi.
 *
 * <p><b>Ide:</b> bila korelasi makespan-energi mendekati 1, meminimalkan makespan
 * hampir otomatis meminimalkan energi (keduanya bukan trade-off nyata). Hal ini
 * terjadi bila energi idle (P_idle x makespan, semua host selalu menyala)
 * mendominasi; {@code dynamicShare} menunjukkan seberapa kecil porsi energi yang
 * benar-benar bergantung pada beban.</p>
 *
 * <p>Program ini berdiri sendiri dan hanya memakai model analitik
 * {@code ScheduleEvaluator} (O(jumlah task) per mapping), tanpa menjalankan
 * CloudSim, sehingga 1000 sampel bisa dihitung dalam waktu singkat. Hasil per
 * sampel ditulis ke results/analysis/makespan_energy_&lt;dataset&gt;.csv.</p>
 *
 * Cara pakai: MakespanEnergyCorrelation [dataset] [sampleCount] [seed]
 */
public final class MakespanEnergyCorrelation {
	/** Folder output CSV detail. */
	private static final Path OUTPUT_DIR = Path.of("results", "analysis");

	// Hanya berisi main dan helper static, constructor private.
	private MakespanEnergyCorrelation() { }

	/**
	 * Titik masuk program.
	 *
	 * <p><b>Alur:</b></p>
	 * <ol>
	 *   <li>Baca argumen: dataset (default GoCJ 500), jumlah sampel (default 1000),
	 *       seed acak (default 42, agar hasil bisa diulang persis).</li>
	 *   <li>Muat panjang task dan bangun evaluator analitik dari desain
	 *       (20 VM, 6 host, penempatan VM yang sama dengan CloudSim).</li>
	 *   <li>Untuk setiap sampel: buat mapping acak (setiap task ke VM acak
	 *       0..47 dengan peluang sama), hitung makespan, energi total, dan porsi
	 *       energi dinamis, lalu tulis satu baris CSV.</li>
	 *   <li>Hitung korelasi Pearson makespan vs energi, serta porsi energi dinamis
	 *       minimum dan maksimum, lalu cetak ringkasannya.</li>
	 * </ol>
	 *
	 * @param args [0] = path dataset, [1] = jumlah sampel, [2] = seed (semua opsional)
	 * @throws IOException bila dataset tidak bisa dibaca atau CSV tidak bisa ditulis
	 */
	public static void main(String[] args) throws IOException {
		Path dataset = Path.of(args.length > 0 ? args[0] : "src/main/resources/dataset/GoCJ_Dataset_500.txt");
		int samples = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
		long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;

		double[] taskLengths = GoCJLoader.load(dataset);
		ScheduleContext context = ScheduleContext.fromDesign(taskLengths);
		ScheduleEvaluator evaluator = new ScheduleEvaluator(context);
		int vmCount = context.vmCount();

		// Seed tetap -> urutan angka acak sama setiap run (reproducible).
		Random random = new Random(seed);
		double[] makespans = new double[samples];
		double[] energies = new double[samples];
		double[] dynamicShares = new double[samples];

		Files.createDirectories(OUTPUT_DIR);
		String name = dataset.getFileName().toString().replaceFirst("\\.txt$", "");
		Path detail = OUTPUT_DIR.resolve("makespan_energy_" + name + ".csv");
		// CREATE + TRUNCATE_EXISTING: file lama ditimpa, bukan ditambahkan.
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(detail,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
			out.println("sample,makespan_s,energy_kwh,dynamic_share");
			for (int s = 0; s < samples; s++) {
				// Mapping acak seragam: setiap task dikirim ke salah satu dari vmCount VM.
				int[] mapping = new int[taskLengths.length];
				for (int t = 0; t < mapping.length; t++) {
					mapping[t] = random.nextInt(vmCount);
				}
				// energyBreakdown memisahkan energi idle (sum_h P_idle_h * makespan) dan dinamis
				// (sum_j (P_max_h - P_idle_h) * (C_j / MIPS_h) * load_j).
				ScheduleEvaluator.EnergyBreakdown breakdown = evaluator.energyBreakdown(mapping);
				// makespan = max_j load_j, load_j = sum L_i / C_j (model antrean sekuensial f1).
				makespans[s] = evaluator.makespanFromLoads(evaluator.vmLoads(mapping));
				energies[s] = breakdown.totalKwh();
				// dynamicShare = energi dinamis / energi total.
				dynamicShares[s] = breakdown.dynamicShare();
				out.printf(Locale.US, "%d,%.6f,%.8f,%.6f%n", s, makespans[s], energies[s], dynamicShares[s]);
			}
		}

		double r = pearson(makespans, energies);
		double minShare = Arrays.stream(dynamicShares).min().orElse(0);
		double maxShare = Arrays.stream(dynamicShares).max().orElse(0);

		System.out.printf(Locale.US, "dataset=%s tasks=%d vms=%d samples=%d seed=%d%n",
				dataset.getFileName(), taskLengths.length, vmCount, samples, seed);
		System.out.printf(Locale.US, "korelasi Pearson(makespan, energy) = %.4f%n", r);
		System.out.printf(Locale.US,
				"komponen energi dinamis / total energi: min=%.4f (%.2f%%) max=%.4f (%.2f%%)%n",
				minShare, minShare * 100, maxShare, maxShare * 100);
		System.out.println("detail -> " + detail);
	}

	/**
	 * Koefisien korelasi Pearson dari dua sampel yang panjangnya sama.
	 *
	 * <p>r = sum (x_i - mean_x)(y_i - mean_y) / sqrt( sum (x_i - mean_x)^2 * sum (y_i - mean_y)^2 ).
	 * Faktor 1/n pada kovarians dan varians saling meniadakan, jadi tidak perlu
	 * dibagi n. Nilai r berada di [-1, 1]: 1 = naik bersama secara linear sempurna,
	 * 0 = tidak ada hubungan linear, -1 = berlawanan arah.</p>
	 *
	 * @param x sampel pertama (makespan)
	 * @param y sampel kedua (energi)
	 * @return koefisien korelasi r (NaN bila salah satu sampel tidak bervariasi)
	 */
	static double pearson(double[] x, double[] y) {
		int n = x.length;
		double meanX = Arrays.stream(x).average().orElse(0);
		double meanY = Arrays.stream(y).average().orElse(0);
		double cov = 0;
		double varX = 0;
		double varY = 0;
		for (int i = 0; i < n; i++) {
			double dx = x[i] - meanX;
			double dy = y[i] - meanY;
			cov += dx * dy;
			varX += dx * dx;
			varY += dy * dy;
		}
		return cov / Math.sqrt(varX * varY);
	}
}
