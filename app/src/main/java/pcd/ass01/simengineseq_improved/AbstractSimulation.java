package pcd.ass01.simengineseq_improved;

import pcd.prova.Barrier;
import pcd.prova.BarrierImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for defining concrete simulations
 *  
 */
public abstract class AbstractSimulation {

	/* environment of the simulation */
	private AbstractEnvironment env;
	
	/* list of the agents */
	private List<AbstractAgent> agents;
	
	/* simulation listeners */
	private List<SimulationListener> listeners;

	/* logical time step */
	private int dt;
	
	/* initial logical time */
	private int t0;

	/* in the case of sync with wall-time */
	private boolean toBeInSyncWithWallTime;
	private int nStepsPerSec;
	
	/* for time statistics*/
	private long currentWallTime;
	private long startWallTime;
	private long endWallTime;
	private long averageTimePerStep;


	protected AbstractSimulation() {
		agents = new ArrayList<AbstractAgent>();
		listeners = new ArrayList<SimulationListener>();
		toBeInSyncWithWallTime = false;
	}
	
	/**
	 * 
	 * Method used to configure the simulation, specifying env and agents
	 * 
	 */
	protected abstract void setup();
	
	/**
	 * Method running the simulation for a number of steps,
	 * using a sequential approach
	 * 
	 * @param numSteps
	 */
	public void run(int numSteps) {		

		startWallTime = System.currentTimeMillis();

		/* initialize the env and the agents inside */
		int t = t0;

		env.init();
		for (var a: agents) {
			a.init(env);
		}

		this.notifyReset(t, agents, env);
		
		long timePerStep = 0;
		int nSteps = 0;

		int nWorkers = Math.min(Runtime.getRuntime().availableProcessors(), agents.size());

		int jobSize = (int) Math.ceil((double) agents.size() / nWorkers);
		Barrier stepBarrier = new BarrierImpl(nWorkers);

		while (nSteps < numSteps) {

			currentWallTime = System.currentTimeMillis();
		
			/* make a step */
			
			env.step(dt);
			
			/* clean the submitted actions */
			
			env.cleanActions();
			
			/* ask each agent to make a step */
			//CONCURRENT
			List<SimulationWorker> workers = new ArrayList<>();
			for (int i = 0; i < nWorkers; i++) {
				int startIndex = i * jobSize;
				int endIndex = Math.min((i + 1) * jobSize, agents.size());
				if(startIndex >= agents.size()) {
					break;
				}
				var simulation = new SimulationWorker("worker-" + (i + 1), agents.subList(startIndex, endIndex), dt, stepBarrier);
				workers.add(simulation);
				simulation.start();

			}

			try {
				stepBarrier.hitAndWaitAll();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}

			for (SimulationWorker worker : workers) {
				try {
					worker.join(); // Wait for the thread to terminate
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}

			t += dt;
			/* process actions submitted to the environment */
			
			env.processActions();
			
			notifyNewStep(t, agents, env);

			nSteps++;			
			timePerStep += System.currentTimeMillis() - currentWallTime;
			
			if (toBeInSyncWithWallTime) {
				syncWithWallTime();
			}
		}	
		
		endWallTime = System.currentTimeMillis();
		this.averageTimePerStep = timePerStep / numSteps;
		
	}
	
	public long getSimulationDuration() {
		return endWallTime - startWallTime;
	}
	
	public long getAverageTimePerCycle() {
		return averageTimePerStep;
	}
	
	/* methods for configuring the simulation */
	
	protected void setupTimings(int t0, int dt) {
		this.dt = dt;
		this.t0 = t0;
	}
	
	protected void syncWithTime(int nCyclesPerSec) {
		this.toBeInSyncWithWallTime = true;
		this.nStepsPerSec = nCyclesPerSec;
	}
		
	protected void setupEnvironment(AbstractEnvironment env) {
		this.env = env;
	}

	protected void addAgent(AbstractAgent agent) {
		agents.add(agent);
	}
	
	/* methods for listeners */
	
	public void addSimulationListener(SimulationListener l) {
		this.listeners.add(l);
	}
	
	private void notifyReset(int t0, List<AbstractAgent> agents, AbstractEnvironment env) {
		for (var l: listeners) {
			l.notifyInit(t0, agents, env);
		}
	}

	private void notifyNewStep(int t, List<AbstractAgent> agents, AbstractEnvironment env) {
		for (var l: listeners) {
			l.notifyStepDone(t, agents, env);
		}
	}

	/* method to sync with wall time at a specified step rate */
	
	private void syncWithWallTime() {
		try {
			long newWallTime = System.currentTimeMillis();
			long delay = 1000 / this.nStepsPerSec;
			long wallTimeDT = newWallTime - currentWallTime;
			if (wallTimeDT < delay) {
				Thread.sleep(delay - wallTimeDT);
			}
		} catch (Exception ex) {}		
	}
}
