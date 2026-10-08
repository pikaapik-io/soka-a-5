package project;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import project.model.FitnessFunction;
import project.model.ScheduleContext;
import project.scheduler.ClonalSelectionScheduler;
import project.scheduler.ConvergenceLog;
import project.scheduler.HarmonySearchScheduler;
import project.scheduler.KpbScheduler;
import project.scheduler.Scheduler;

/**
 * Command line of {@link Experiment}.
 *
 * Legacy form (unchanged): Experiment [dataset] [k_percent], output to results/simulation/.
 * Flag form: any argument starting with "--"; output to a new session folder.
 */
final class RunOptions {
	static final String USAGE = String.join("\n",
			"Usage: Experiment [dataset] [k_percent]                 (legacy KPB, output results/simulation/)",
			"       Experiment --dataset FILE [--algo kpb|hs|csa] [options]",
			"",
			"  --algo A          kpb (default), hs, csa",
			"  --k K             KPB k percent (default " + KpbScheduler.DEFAULT_K_PERCENT + ")",
			"  --seed S          HS/CSA random seed (default 1)",
			"  --evals N         HS/CSA fitness evaluations (default " + HarmonySearchScheduler.DEFAULT_MAX_EVALUATIONS + ")",
			"  --w W             fitness weight of makespan vs energy (default " + FitnessFunction.DEFAULT_W + ")",
			"  --hms --hmcr --par                     HS parameters (default "
					+ HarmonySearchScheduler.DEFAULT_HMS + " / " + HarmonySearchScheduler.DEFAULT_HMCR + " / "
					+ HarmonySearchScheduler.DEFAULT_PAR + ")",
			"  --pop --nselect --beta --mut-min --mut-max --d   CSA parameters (default "
					+ ClonalSelectionScheduler.DEFAULT_POP_SIZE + " / " + ClonalSelectionScheduler.DEFAULT_N_SELECT
					+ " / " + ClonalSelectionScheduler.DEFAULT_BETA + " / " + ClonalSelectionScheduler.DEFAULT_MUT_MIN
					+ " / " + ClonalSelectionScheduler.DEFAULT_MUT_MAX + " / " + ClonalSelectionScheduler.DEFAULT_D + ")",
			"  --log-every N     convergence log interval in evaluations (default " + ConvergenceLog.DEFAULT_EVERY + ")",
			"  --out DIR         session folder (default results/sessions/<timestamp>)",
			"  --export-mapping FILE   also write the mapping JSON here (read by realworld/kpb_realworld.py)",
			"  --no-sim          schedule and export only, skip CloudSim");

	boolean legacy;
	Path dataset;
	String algo = "kpb";
	double kPercent = KpbScheduler.DEFAULT_K_PERCENT;
	long seed = 1;
	int evals = HarmonySearchScheduler.DEFAULT_MAX_EVALUATIONS;
	double w = FitnessFunction.DEFAULT_W;
	int hms = HarmonySearchScheduler.DEFAULT_HMS;
	double hmcr = HarmonySearchScheduler.DEFAULT_HMCR;
	double par = HarmonySearchScheduler.DEFAULT_PAR;
	int pop = ClonalSelectionScheduler.DEFAULT_POP_SIZE;
	int nSelect = ClonalSelectionScheduler.DEFAULT_N_SELECT;
	double beta = ClonalSelectionScheduler.DEFAULT_BETA;
	double mutMin = ClonalSelectionScheduler.DEFAULT_MUT_MIN;
	double mutMax = ClonalSelectionScheduler.DEFAULT_MUT_MAX;
	int d = ClonalSelectionScheduler.DEFAULT_D;
	int logEvery = ConvergenceLog.DEFAULT_EVERY;
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
				case "--algo": o.algo = value(args, ++i, flag).toLowerCase(Locale.ROOT); break;
				case "--k": o.kPercent = Double.parseDouble(value(args, ++i, flag)); break;
				case "--seed": o.seed = Long.parseLong(value(args, ++i, flag)); break;
				case "--evals": o.evals = Integer.parseInt(value(args, ++i, flag)); break;
				case "--w": o.w = Double.parseDouble(value(args, ++i, flag)); break;
				case "--hms": o.hms = Integer.parseInt(value(args, ++i, flag)); break;
				case "--hmcr": o.hmcr = Double.parseDouble(value(args, ++i, flag)); break;
				case "--par": o.par = Double.parseDouble(value(args, ++i, flag)); break;
				case "--pop": o.pop = Integer.parseInt(value(args, ++i, flag)); break;
				case "--nselect": o.nSelect = Integer.parseInt(value(args, ++i, flag)); break;
				case "--beta": o.beta = Double.parseDouble(value(args, ++i, flag)); break;
				case "--mut-min": o.mutMin = Double.parseDouble(value(args, ++i, flag)); break;
				case "--mut-max": o.mutMax = Double.parseDouble(value(args, ++i, flag)); break;
				case "--d": o.d = Integer.parseInt(value(args, ++i, flag)); break;
				case "--log-every": o.logEvery = Integer.parseInt(value(args, ++i, flag)); break;
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

	Scheduler createScheduler(ScheduleContext topology) {
		switch (algo) {
			case "kpb":
				return new KpbScheduler(kPercent);
			case "hs":
				return new HarmonySearchScheduler(topology, w, hms, hmcr, par, evals, seed, logEvery);
			case "csa":
				return new ClonalSelectionScheduler(topology, w, pop, nSelect, beta, mutMin, mutMax, d, evals,
						seed, logEvery);
			default:
				throw new IllegalArgumentException("Unknown --algo " + algo + " (kpb, hs, csa)");
		}
	}

	/** File-name tag of one run, e.g. GoCJ_Dataset_500_kpb_k100 or GoCJ_Dataset_500_csa_seed3. */
	String tag(String datasetName) {
		return "kpb".equals(algo)
				? datasetName + "_kpb_k" + new java.text.DecimalFormat("0.##").format(kPercent)
				: datasetName + "_" + algo + "_seed" + seed;
	}
}
