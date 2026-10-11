package project;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import project.scheduler.KpbScheduler;

/**
 * Command line of {@link Experiment}.
 *
 * Legacy form (unchanged): Experiment [dataset] [k_percent], output to results/simulation/.
 * Flag form: any argument starting with "--"; output to a new session folder.
 */
final class RunOptions {
	static final String USAGE = String.join("\n",
			"Usage: Experiment [dataset] [k_percent]                 (output results/simulation/)",
			"       Experiment --dataset FILE [--k K] [options]",
			"",
			"  --k K             KPB k percent (default " + KpbScheduler.DEFAULT_K_PERCENT + ")",
			"  --out DIR         session folder (default results/sessions/<timestamp>)",
			"  --export-mapping FILE   also write the mapping JSON here (read by realworld/kpb_realworld.py)",
			"  --no-sim          schedule and export only, skip CloudSim");

	boolean legacy;
	Path dataset;
	double kPercent = KpbScheduler.DEFAULT_K_PERCENT;
	Path out;
	Path exportMapping;
	boolean noSim;
	boolean help;

	static RunOptions parse(String[] args) {
		RunOptions o = new RunOptions();
		boolean flags = false;
		for (String arg : args) {
			flags |= arg.startsWith("--");
		}
		if (!flags) {
			o.legacy = true;
			o.dataset = args.length > 0 ? Path.of(args[0]) : null;
			if (args.length > 1) {
				o.kPercent = Double.parseDouble(args[1]);
			}
			return o;
		}
		for (int i = 0; i < args.length; i++) {
			String flag = args[i];
			switch (flag) {
				case "--help": o.help = true; break;
				case "--no-sim": o.noSim = true; break;
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

	/** File-name tag of one run, e.g. GoCJ_Dataset_500_kpb_k100. */
	String tag(String datasetName) {
		return datasetName + "_kpb_k" + new java.text.DecimalFormat("0.##").format(kPercent);
	}
}
