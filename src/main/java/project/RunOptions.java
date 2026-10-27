package project;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import project.scheduler.KpbScheduler;


final class RunOptions {
	/** Teks bantuan yang dicetak untuk --help dan disertakan di pesan error argumen. */
	static final String USAGE = String.join("\n",
			"Usage: Experiment [dataset] [k_percent]                 (output results/simulation/)",
			"       Experiment --dataset FILE [--k K] [options]",
			"",
			"  --k K             KPB k percent (default " + KpbScheduler.DEFAULT_K_PERCENT + ")",
			"  --out DIR         session folder (default results/sessions/<timestamp>)",
			"  --export-mapping FILE   also write the mapping JSON here (read by realworld/kpb_realworld.py)",
			"  --no-sim          schedule and export only, skip CloudSim");

	/** true bila dipanggil dengan bentuk legacy (argumen posisi, output ke results/simulation/). */
	boolean legacy;
	/** File dataset GoCJ; null pada bentuk legacy tanpa argumen (memakai 5 task contoh bawaan). */
	Path dataset;
	/** Nilai k KPB dalam persen; default {@link KpbScheduler#DEFAULT_K_PERCENT}. */
	double kPercent = KpbScheduler.DEFAULT_K_PERCENT;
	/** Folder sesi untuk output bentuk flag (summary.csv, placement.csv, cloudlets_*.csv, mapping_*.json). */
	Path out;
	/** Path JSON mapping tambahan (dipakai runner real-world); null bila tidak diminta. */
	Path exportMapping;
	/** true = hanya jadwalkan + ekspor mapping + cetak prediksi analitik, tanpa simulasi CloudSim. */
	boolean noSim;
	/** true = cetak USAGE lalu keluar. */
	boolean help;

	
	static RunOptions parse(String[] args) {
		RunOptions o = new RunOptions();
		// Deteksi bentuk pemanggilan: cukup satu argumen "--..." untuk masuk mode flag.
		boolean flags = false;
		for (String arg : args) {
			flags |= arg.startsWith("--");
		}
		if (!flags) {
			// Bentuk legacy: argumen posisi. Tanpa argumen sama sekali -> dataset contoh bawaan.
			o.legacy = true;
			o.dataset = args.length > 0 ? Path.of(args[0]) : null;
			if (args.length > 1) {
				o.kPercent = Double.parseDouble(args[1]);
			}
			return o;
		}
		// Bentuk flag: proses argumen dari kiri ke kanan.
		for (int i = 0; i < args.length; i++) {
			String flag = args[i];
			switch (flag) {
				case "--help": o.help = true; break;
				case "--no-sim": o.noSim = true; break;
				// ++i: lompat ke argumen berikutnya yang berisi nilai flag ini.
				case "--dataset": o.dataset = Path.of(value(args, ++i, flag)); break;
				case "--k": o.kPercent = Double.parseDouble(value(args, ++i, flag)); break;
				case "--out": o.out = Path.of(value(args, ++i, flag)); break;
				case "--export-mapping": o.exportMapping = Path.of(value(args, ++i, flag)); break;
				default: throw new IllegalArgumentException("Unknown option " + flag + "\n" + USAGE);
			}
		}
		if (!o.help && o.dataset == null) {
			throw new IllegalArgumentException("--dataset is required\n" + USAGE);
		}
		// Folder sesi default bertimestamp, supaya setiap run tidak menimpa hasil run sebelumnya.
		if (o.out == null) {
			o.out = Path.of("results", "sessions",
					LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
		}
		return o;
	}

	
	private static String value(String[] args, int index, String flag) {
		if (index >= args.length) {
			throw new IllegalArgumentException(flag + " needs a value");
		}
		return args[index];
	}

	
	String tag(String datasetName) {
		return datasetName + "_kpb_k" + new java.text.DecimalFormat("0.##").format(kPercent);
	}
}
