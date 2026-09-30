package project.metrics;

public final class Result {
	private final String scheduler;
	private final double makespan;
	private final double averageResponse;
	private final double throughput;
	private final double[] vmLoads;

	public Result(String scheduler, double makespan, double averageResponse,
				  double throughput, double[] vmLoads) {
		this.scheduler = scheduler;
		this.makespan = makespan;
		this.averageResponse = averageResponse;
		this.throughput = throughput;
		this.vmLoads = vmLoads.clone();
	}

	public String scheduler() { return scheduler; }
	public double makespan() { return makespan; }
	public double averageResponse() { return averageResponse; }
	public double throughput() { return throughput; }
	public double[] vmLoads() { return vmLoads.clone(); }
}
