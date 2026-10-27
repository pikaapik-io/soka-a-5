package project.analysis;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.util.Log;
import org.slf4j.LoggerFactory;
import project.data.GoCJLoader;
import project.metrics.MetricsCollector;
import project.metrics.Result;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;
import project.scheduler.KpbScheduler;
import project.scheduler.Scheduler;
import project.setup.CloudSimAdapter;
import project.setup.SimulationSetup;
import project.setup.VmFactory;

/**
 * Verifikasi Fase 3: untuk mapping hasil KPB, membandingkan makespan/energi
 * analitik dari {@code ScheduleEvaluator} dengan hasil run CloudSim yang
 * sebenarnya. Syarat: selisih energi di bawah 3%.
 *
 * <p><b>Tujuan:</b> membuktikan bahwa model analitik (rumus f1 untuk makespan
 * dan rumus energi bagian 3.2) cukup akurat mewakili CloudSim, sehingga baris
 * "prediksi analitik" di setiap run bisa dipercaya. Program ini berdiri sendiri
 * (punya {@code main}) dan tidak dipakai oleh alur {@code Experiment}.</p>
 *
 * <p>Output: satu baris tabel per kombinasi (dataset, k) berisi makespan CloudSim
 * (_cs), makespan evaluator (_ev), selisih %, lalu hal yang sama untuk energi.</p>
 *
 * Cara pakai: EvaluatorCheck [dataset1,dataset2,...] [k1,k2,...]
 */
public final class EvaluatorCheck {
	// Hanya berisi main dan helper static, constructor private.
	private EvaluatorCheck() { }

	/**
	 * Titik masuk program.
	 *
	 * <p>Alur: atur level log -&gt; baca daftar dataset (argumen 1, dipisah koma;
	 * default GoCJ 100, 500, 1000) dan daftar k% (argumen 2; default 20, 50, 100)
	 * -&gt; cetak header tabel -&gt; untuk setiap dataset dan setiap k panggil
	 * {@link #check}.</p>
	 *
	 * @param args [0] = daftar path dataset dipisah koma, [1] = daftar k% dipisah koma (opsional)
	 * @throws IOException bila file dataset tidak bisa dibaca
	 */
	public static void main(String[] args) throws IOException {
		// Log CloudSim default "error" (bisa diubah dengan -Dlog=...), agar output hanya tabel hasil.
		Log.setLevel(Level.toLevel(System.getProperty("log", "error"), Level.ERROR));
		// Peringatan kontensi RAM/BW dari CloudletScheduler muncul tiap tick, jadi selalu dibatasi ke ERROR.
		Log.setLevel(LoggerFactory.getLogger(CloudletScheduler.class.getSimpleName()), Level.ERROR);
		String[] datasets = args.length > 0 ? args[0].split(",")
				: new String[] {"src/main/resources/dataset/GoCJ_Dataset_100.txt",
						"src/main/resources/dataset/GoCJ_Dataset_500.txt",
						"src/main/resources/dataset/GoCJ_Dataset_1000.txt"};
		double[] kPercents = args.length > 1
				? Arrays.stream(args[1].split(",")).mapToDouble(Double::parseDouble).toArray()
				: new double[] {20, 50, 100};

		System.out.printf(Locale.US, "%-24s %5s | %11s %11s %7s | %10s %10s %7s%n",
				"dataset", "k%", "makespan_cs", "makespan_ev", "diff%", "energy_cs", "energy_ev", "diff%");
		for (String datasetArg : datasets) {
			Path path = Path.of(datasetArg.trim());
			double[] taskLengths = GoCJLoader.load(path);
			for (double k : kPercents) {
				check(path, taskLengths, k);
			}
		}
	}

	/**
	 * Satu perbandingan untuk satu dataset dan satu nilai k.
	 *
	 * <p><b>Alur:</b></p>
	 * <ol>
	 *   <li>Jalankan KPB(k) dengan panjang task dan kapasitas 20 VM -&gt; mapping.</li>
	 *   <li>Jalankan simulasi CloudSim penuh dengan mapping tersebut dan ambil
	 *       metriknya (waktu penjadwalan diisi 0 karena tidak diukur di sini).</li>
	 *   <li>Hitung makespan dan energi analitik dengan {@code ScheduleEvaluator};
	 *       konteksnya dari {@code ScheduleContext.fromDesign}, yang memakai
	 *       penempatan VM -&gt; host yang sama dengan CloudSim.</li>
	 *   <li>Selisih relatif = 100 * |analitik - CloudSim| / CloudSim, untuk makespan
	 *       dan energi, lalu cetak satu baris.</li>
	 * </ol>
	 *
	 * @param path        path dataset (hanya untuk nama file di output)
	 * @param taskLengths panjang task dalam MI
	 * @param k           persentase VM terbaik yang dipertimbangkan KPB
	 */
	private static void check(Path path, double[] taskLengths, double k) {
		Scheduler scheduler = new KpbScheduler(k);
		double[] vmCapacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());
		int[] mapping = scheduler.schedule(taskLengths, vmCapacities);

		// Hasil "nyata" dari simulasi CloudSim.
		SimulationSetup setup = new SimulationSetup(taskLengths);
		setup.run(mapping);
		Result result = MetricsCollector.collect(scheduler.getName(), setup, 0);

		// Prediksi analitik untuk mapping yang sama.
		ScheduleEvaluator evaluator = new ScheduleEvaluator(ScheduleContext.fromDesign(taskLengths));
		double evalMakespan = evaluator.makespan(mapping);
		double evalEnergy = evaluator.energyKwh(mapping);

		// Selisih relatif (persen) terhadap hasil CloudSim.
		double makespanDiff = 100 * Math.abs(evalMakespan - result.makespan()) / result.makespan();
		double energyDiff = 100 * Math.abs(evalEnergy - result.energyKwh()) / result.energyKwh();

		System.out.printf(Locale.US, "%-24s %5.0f | %11.4f %11.4f %6.2f%% | %10.6f %10.6f %6.2f%%%n",
				path.getFileName(), k, result.makespan(), evalMakespan, makespanDiff,
				result.energyKwh(), evalEnergy, energyDiff);
	}
}
