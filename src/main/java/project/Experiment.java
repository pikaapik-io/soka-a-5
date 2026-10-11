package project;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterCharacteristics;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletScheduler;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.util.Log;
import org.slf4j.LoggerFactory;
import project.data.GoCJLoader;
import project.metrics.MetricsCollector;
import project.metrics.Result;
import project.model.ScheduleContext;
import project.model.ScheduleEvaluator;
import project.scheduler.KpbScheduler;
import project.setup.CloudSimAdapter;
import project.setup.DatacenterFactory;
import project.setup.SimulationSetup;
import project.setup.VmFactory;

/**
 * Runs KPB on the CloudSim Plus scenario of the project design. See {@link RunOptions#USAGE} (-Dlog=error hides the CloudSim log).
 */
public final class Experiment {
	private static final Path LEGACY_OUTPUT_DIR = Path.of("results", "simulation");
	private static final String SUMMARY_HEADER = "timestamp,dataset,tasks,k_percent,makespan_s,"
			+ "analytic_makespan_s,energy_kwh,avg_response_s,utilization_pct,di_all_vm,di_used_vm,"
			+ "throughput_task_s,cost_usd,scheduling_ms";

	private Experiment() { }

	public static void main(String[] args) throws IOException {
		// CloudSim log (DC start, VM -> host allocation, cloudlet -> VM). Silence with -Dlog=error.
		Log.setLevel(Level.toLevel(System.getProperty("log", "info"), Level.INFO));
		// RAM/BW contention warnings are logged per cloudlet every tick and would flood the output.
		Log.setLevel(LoggerFactory.getLogger(CloudletScheduler.class.getSimpleName()), Level.ERROR);
		RunOptions options = RunOptions.parse(args);
		if (options.help) {
			System.out.println(RunOptions.USAGE);
			return;
		}
		double[] taskLengths = options.dataset == null
				? new double[] {900_000, 600_000, 450_000, 300_000, 150_000}
				: GoCJLoader.load(options.dataset);
		String datasetName = options.dataset == null ? "example"
				: options.dataset.getFileName().toString().replaceFirst("\\.txt$", "");

		// The scheduler only sees task lengths and VM capacities (MIPS x PE).
		double[] vmCapacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());
		KpbScheduler scheduler = new KpbScheduler(options.kPercent);
		scheduler.schedule(taskLengths, vmCapacities); // JIT warm-up for metric 8
		long start = System.nanoTime();
		int[] mapping = scheduler.schedule(taskLengths, vmCapacities);
		double schedulingMillis = (System.nanoTime() - start) / 1e6;

		ScheduleEvaluator evaluator = new ScheduleEvaluator(
				ScheduleContext.fromDesign(new double[0]).withTasks(taskLengths, vmCapacities));
		RunInfo info = new RunInfo(options, scheduler, datasetName, taskLengths.length, schedulingMillis,
				evaluator.makespan(mapping), evaluator.energyKwh(mapping));

		if (options.exportMapping != null) {
			writeMappingJson(options.exportMapping, info, vmCapacities, mapping);
		} else if (!options.legacy) {
			writeMappingJson(options.out.resolve("mapping_" + options.tag(datasetName) + ".json"),
					info, vmCapacities, mapping);
		}
		if (options.noSim) {
			System.out.printf(Locale.US, "scheduler=%s%n", scheduler.getName());
			System.out.printf(Locale.US, "analitik: makespan=%.3f s, energy=%.6f kWh%n",
					info.analyticMakespan, info.analyticEnergyKwh);
			System.out.printf(Locale.US, "schedulingTime=%.3f ms%n", schedulingMillis);
			return;
		}

		SimulationSetup setup = new SimulationSetup(taskLengths);
		setup.run(mapping);
		Result result = MetricsCollector.collect(scheduler.getName(), setup, schedulingMillis);

		printInfrastructure(setup);
		System.out.printf(Locale.US, "scheduler=%s%n", result.scheduler());
		System.out.printf(Locale.US, "tasks=%d, vmsCreated=%d/%d, cloudletsFinished=%d/%d%n",
				result.tasks(), setup.broker().getVmCreatedList().size(), setup.vmList().size(),
				setup.cloudlets().stream().filter(Cloudlet::isFinished).count(), result.tasks());
		System.out.printf(Locale.US, "makespan=%.3f s (analitik %.3f s), energy=%.4f kWh, avgResponse=%.3f s%n",
				result.makespan(), info.analyticMakespan, result.energyKwh(), result.averageResponse());
		System.out.printf(Locale.US, "utilization=%.2f%%, DI=%.4f (VM terpakai %.4f), throughput=%.4f task/s%n",
				result.utilizationPercent(), result.degreeOfImbalance(),
				result.degreeOfImbalanceUsedVms(), result.throughput());
		System.out.printf(Locale.US, "cost=$%.2f, schedulingTime=%.3f ms%n",
				result.totalCost(), result.schedulingMillis());
		if (!options.legacy) {
			// Same line format as realworld/kpb_realworld.py, so one parser (analyze_grid.py) reads both.
			long vmsUsed = Arrays.stream(result.vmFinishTimes()).filter(t -> t > 0).count();
			System.out.printf(Locale.US, "prediksi analitik ±%.3f s%n", info.analyticMakespan);
			System.out.printf(Locale.US, "  makespan=%.3fs (nyata/prediksi=%.3fx), energy=%.6f kWh, avgResponse=%.3fs, "
							+ "utilization=%.2f%%, DI=%.4f (VM terpakai %.4f), throughput=%.3f task/s, cost=$%.2f, "
							+ "schedulingTime=%.3f ms, vmsUsed=%d/%d%n",
					result.makespan(), result.makespan() / info.analyticMakespan, result.energyKwh(),
					result.averageResponse(), result.utilizationPercent(), result.degreeOfImbalance(),
					result.degreeOfImbalanceUsedVms(), result.throughput(), result.totalCost(),
					result.schedulingMillis(), vmsUsed, setup.vmList().size());
		}
		System.out.println("mapping=" + Arrays.toString(mapping));
		System.out.println("vmFinish=" + Arrays.toString(round(result.vmFinishTimes())));

		if (options.legacy) {
			if (options.dataset != null) {
				writeCsv(LEGACY_OUTPUT_DIR, SUMMARY_HEADER, summaryRow(datasetName,
								String.format(Locale.US, "%.1f", options.kPercent), result, info.analyticMakespan), setup,
						String.format(Locale.US, "cloudlets_%s_k%s.csv", datasetName,
								new java.text.DecimalFormat("0.##").format(options.kPercent)));
			}
		} else {
			String row = summaryRow(datasetName, String.format(Locale.US, "%.1f", options.kPercent), result,
					info.analyticMakespan);
			writeCsv(options.out, SUMMARY_HEADER, row, setup, "cloudlets_" + options.tag(datasetName) + ".csv");
			System.out.println("hasil -> " + options.out);
		}
	}

	/** What a run reports besides the CloudSim metrics; also the content of the mapping JSON. */
	private static final class RunInfo {
		final String scheduler;
		final String dataset;
		final int tasks;
		final double kPercent;
		final double schedulingMillis;
		final double analyticMakespan;
		final double analyticEnergyKwh;

		RunInfo(RunOptions o, KpbScheduler s, String dataset, int tasks, double schedulingMillis,
				double analyticMakespan, double analyticEnergyKwh) {
			this.scheduler = s.getName();
			this.dataset = dataset;
			this.tasks = tasks;
			this.kPercent = o.kPercent;
			this.schedulingMillis = schedulingMillis;
			this.analyticMakespan = analyticMakespan;
			this.analyticEnergyKwh = analyticEnergyKwh;
		}
	}

	private static void writeMappingJson(Path path, RunInfo info, double[] vmCapacities, int[] mapping)
			throws IOException {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("scheduler", info.scheduler);
		json.put("dataset", info.dataset);
		json.put("tasks", info.tasks);
		json.put("k_percent", info.kPercent);
		json.put("analytic_makespan_s", info.analyticMakespan);
		json.put("analytic_energy_kwh", info.analyticEnergyKwh);
		json.put("scheduling_ms", info.schedulingMillis);
		json.put("vm_capacity", vmCapacities);
		json.put("mapping", mapping);
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		new ObjectMapper().writeValue(path.toFile(), json);
	}

	/** Datacenter, host and VM specs read back from the CloudSim objects, to compare with the design. */
	private static void printInfrastructure(SimulationSetup setup) {
		System.out.println("=== Infrastruktur CloudSim ===");
		for (Datacenter dc : setup.datacenters()) {
			DatacenterCharacteristics ch = dc.getCharacteristics();
			String name = DatacenterFactory.typeOf(dc.getHost(0)) == DatacenterFactory.HostType.A_PERFORMANCE
					? "Performance" : "Efficiency";
			System.out.printf(Locale.US, "DC-%d %s | %s / %s / %s | %d host | $%.2f/s, $%.2f/GB RAM, "
							+ "$%.4f/GB storage, $%.3f/Mbps BW%n",
					dc.getId(), name, ch.getArchitecture(), ch.getOs(), ch.getVmm(), dc.getHostList().size(),
					ch.getCostPerSecond(), ch.getCostPerMem() * 1024, ch.getCostPerStorage() * 1024,
					ch.getCostPerBw());
			for (Host host : dc.getHostList()) {
				// VMs are already deallocated when the simulation ends, so group by vm.getHost().
				List<Vm> vms = setup.vmList().stream().filter(vm -> vm.getHost() == host).collect(Collectors.toList());
				long usedPes = vms.stream().mapToLong(Vm::getNumberOfPes).sum();
				System.out.printf(Locale.US, "  Host %d | %d PE x %.0f MIPS | RAM %d GB | BW %d Mbps | "
								+ "P idle %.0f W, P max %.0f W | PE terpakai %d/%d%n",
						host.getId(), host.getNumberOfPes(), host.getMips(), host.getRam().getCapacity() / 1024,
						host.getBw().getCapacity(), host.getPowerModel().getPower(0),
						host.getPowerModel().getPower(1), usedPes, host.getNumberOfPes());
				for (Vm vm : vms) {
					System.out.printf(Locale.US, "    vm%-2d %s | %d PE x %.0f MIPS | RAM %d GB | BW %d Mbps%n",
							vm.getId(), vm.getDescription(), vm.getNumberOfPes(), vm.getMips(),
							vm.getRam().getCapacity() / 1024, vm.getBw().getCapacity());
				}
			}
		}
		System.out.printf("Total: %d datacenter, %d host, %d VM%n%n",
				setup.datacenters().size(), setup.hosts().size(), setup.vmList().size());
	}

	private static double[] round(double[] values) {
		return Arrays.stream(values).map(v -> Math.round(v * 100) / 100.0).toArray();
	}

	private static String summaryRow(String dataset, String kPercent, Result r, double analyticMakespan) {
		return String.format(Locale.US, "%s,%s,%d,%s,%.4f,%.4f,%.6f,%.4f,%.2f,%.4f,%.4f,%.4f,%.4f,%.3f",
				LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), dataset, r.tasks(), kPercent,
				r.makespan(), analyticMakespan, r.energyKwh(), r.averageResponse(),
				r.utilizationPercent(), r.degreeOfImbalance(), r.degreeOfImbalanceUsedVms(),
				r.throughput(), r.totalCost(), r.schedulingMillis());
	}

	private static void writeCsv(Path dir, String header, String row, SimulationSetup setup, String detailName)
			throws IOException {
		Files.createDirectories(dir);
		Path summary = dir.resolve("summary.csv");
		if (Files.exists(summary)) {
			String existing;
			try (java.io.BufferedReader reader = Files.newBufferedReader(summary)) {
				existing = reader.readLine();
			}
			if (!header.equals(existing)) {
				throw new IllegalStateException(summary + " has a different header; use a new --out folder");
			}
		} else {
			Files.writeString(summary, header + System.lineSeparator());
		}
		Files.writeString(summary, row + System.lineSeparator(), StandardOpenOption.APPEND);

		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(dir.resolve("placement.csv")))) {
			out.println("vm,vm_type,pes,mips_per_pe,ram_mb,bw_mbps,datacenter,host");
			for (Vm vm : setup.vmList()) {
				out.printf(Locale.US, "%d,%s,%d,%.0f,%d,%d,%d,%d%n", vm.getId(), vm.getDescription(),
						vm.getNumberOfPes(), vm.getMips(), vm.getRam().getCapacity(), vm.getBw().getCapacity(),
						vm.getHost().getDatacenter().getId(), vm.getHost().getId());
			}
		}

		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(dir.resolve(detailName)))) {
			out.println("task_id,length_mi,vm,vm_type,datacenter,host,start_s,finish_s");
			for (Cloudlet c : setup.cloudlets()) {
				Vm vm = c.getVm();
				out.printf(Locale.US, "%d,%d,%d,%s,%d,%d,%.4f,%.4f%n", c.getId(), c.getTotalLength(), vm.getId(),
						vm.getDescription(), vm.getHost().getDatacenter().getId(), vm.getHost().getId(),
						c.getExecStartTime(), c.getFinishTime());
			}
		}
	}
}
