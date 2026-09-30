package project.metrics;

public final class EnergyMeter {
	private EnergyMeter() { }

	public static double estimateKwh(double[] hostCpuSeconds, double[] hostCapacity,
									 double[] idleWatts, double[] maxWatts,
									 double makespan) {
		if (hostCpuSeconds.length != hostCapacity.length
				|| hostCapacity.length != idleWatts.length
				|| idleWatts.length != maxWatts.length) {
			throw new IllegalArgumentException("Host arrays must have equal lengths");
		}
		double joules = 0;
		for (int host = 0; host < hostCpuSeconds.length; host++) {
			double utilization = makespan <= 0 || hostCapacity[host] <= 0 ? 0
					: Math.min(1, hostCpuSeconds[host] / (hostCapacity[host] * makespan));
			joules += (idleWatts[host] + (maxWatts[host] - idleWatts[host]) * utilization)
					* makespan;
		}
		return joules / 3_600_000;
	}
}
