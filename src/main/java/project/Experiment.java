package project;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import project.model.FitnessEvaluator;
import project.scheduler.KpbScheduler;
import project.scheduler.Scheduler;
import project.setup.CloudSimAdapter;
import project.setup.DatacenterFactory;
import project.setup.SimulationSetup;
import project.setup.VmFactory;

/**
 * Runs KPB on the CloudSim Plus scenario of the project design.
 *
 * Usage: Experiment [dataset] [k_percent]   (-Dlog=error hides the CloudSim log)
 */
public final class Experiment {
	private static final Path OUTPUT_DIR = Path.of("results", "simulation");

	private Experiment() { }

	public static void main(String[] args) throws IOException {
		// CloudSim log (DC start, VM -> host allocation, cloudlet -> VM). Silence with -Dlog=error.
		Log.setLevel(Level.toLevel(System.getProperty("log", "info"), Level.INFO));
		// RAM/BW contention warnings are logged per cloudlet every tick and would flood the output.
		Log.setLevel(LoggerFactory.getLogger(CloudletScheduler.class.getSimpleName()), Level.ERROR);
		Path dataset = args.length == 0 ? null : Path.of(args[0]);
		double[] taskLengths = dataset == null
				? new double[] {900_000, 600_000, 450_000, 300_000, 150_000}
				: GoCJLoader.load(dataset);
		double kPercent = args.length > 1
				? Double.parseDouble(args[1])
				: KpbScheduler.DEFAULT_K_PERCENT;

		// The scheduler only sees task lengths and VM capacities (MIPS x PE).
		double[] vmCapacities = CloudSimAdapter.vmCapacities(VmFactory.createDefaultVms());
		Scheduler scheduler = new KpbScheduler(kPercent);
		scheduler.schedule(taskLengths, vmCapacities); // JIT warm-up for metric 8
		long start = System.nanoTime();
		int[] mapping = scheduler.schedule(taskLengths, vmCapacities);
		double schedulingMillis = (System.nanoTime() - start) / 1e6;

		SimulationSetup setup = new SimulationSetup(taskLengths);
		setup.run(mapping);
		Result result = MetricsCollector.collect(scheduler.getName(), setup, schedulingMillis);
		double analyticMakespan = new FitnessEvaluator(taskLengths, vmCapacities).makespan(mapping);

		printInfrastructure(setup);
		System.out.printf(Locale.US, "scheduler=%s%n", result.scheduler());
		System.out.printf(Locale.US, "tasks=%d, vmsCreated=%d/%d, cloudletsFinished=%d/%d%n",
				result.tasks(), setup.broker().getVmCreatedList().size(), setup.vmList().size(),
				setup.cloudlets().stream().filter(Cloudlet::isFinished).count(), result.tasks());
		System.out.printf(Locale.US, "makespan=%.3f s (analitik %.3f s), energy=%.4f kWh, avgResponse=%.3f s%n",
				result.makespan(), analyticMakespan, result.energyKwh(), result.averageResponse());
		System.out.printf(Locale.US, "utilization=%.2f%%, DI=%.4f (VM terpakai %.4f), throughput=%.4f task/s%n",
				result.utilizationPercent(), result.degreeOfImbalance(),
				result.degreeOfImbalanceUsedVms(), result.throughput());
		System.out.printf(Locale.US, "cost=$%.2f, schedulingTime=%.3f ms%n",
				result.totalCost(), result.schedulingMillis());
		System.out.println("mapping=" + Arrays.toString(mapping));
		System.out.println("vmFinish=" + Arrays.toString(round(result.vmFinishTimes())));

		if (dataset != null) {
			String name = dataset.getFileName().toString().replaceFirst("\\.txt$", "");
			writeCsv(name, kPercent, result, analyticMakespan, setup);
		}
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

	private static void writeCsv(String dataset, double kPercent, Result r, double analyticMakespan,
								 SimulationSetup setup) throws IOException {
		Files.createDirectories(OUTPUT_DIR);
		Path summary = OUTPUT_DIR.resolve("summary.csv");
		boolean newFile = !Files.exists(summary);
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(summary,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
			if (newFile) {
				out.println("timestamp,dataset,tasks,k_percent,makespan_s,analytic_makespan_s,energy_kwh,"
						+ "avg_response_s,utilization_pct,di_all_vm,di_used_vm,throughput_task_s,cost_usd,scheduling_ms");
			}
			out.printf(Locale.US, "%s,%s,%d,%.1f,%.4f,%.4f,%.6f,%.4f,%.2f,%.4f,%.4f,%.4f,%.4f,%.3f%n",
					LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS), dataset, r.tasks(), kPercent,
					r.makespan(), analyticMakespan, r.energyKwh(), r.averageResponse(),
					r.utilizationPercent(), r.degreeOfImbalance(), r.degreeOfImbalanceUsedVms(),
					r.throughput(), r.totalCost(), r.schedulingMillis());
		}

		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(OUTPUT_DIR.resolve("placement.csv")))) {
			out.println("vm,vm_type,pes,mips_per_pe,ram_mb,bw_mbps,datacenter,host");
			for (Vm vm : setup.vmList()) {
				out.printf(Locale.US, "%d,%s,%d,%.0f,%d,%d,%d,%d%n", vm.getId(), vm.getDescription(),
						vm.getNumberOfPes(), vm.getMips(), vm.getRam().getCapacity(), vm.getBw().getCapacity(),
						vm.getHost().getDatacenter().getId(), vm.getHost().getId());
			}
		}

		Path detail = OUTPUT_DIR.resolve(String.format(Locale.US, "cloudlets_%s_k%s.csv",
				dataset, new java.text.DecimalFormat("0.##").format(kPercent)));
		try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(detail))) {
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
