package info.openrocket.core.simulation.listeners;

import info.openrocket.core.motor.MotorConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.MotorClusterState;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;

public interface SimulationEventListener {

	/**
	 * Called before adding a flight event to the event queue.
	 * 
	 * @param status the simulation status
	 * @param event  the event that is being added
	 * @return <code>true</code> to add the event,
	 *         <code>false</code> to abort adding event to event queue
	 */
	public boolean addFlightEvent(SimulationStatus status, FlightEvent event) throws SimulationException;

	/**
	 * Called before handling a flight event.
	 * 
	 * @param status the simulation status
	 * @param event  the event that is taking place
	 * @return <code>true</code> to continue handling the event,
	 *         <code>false</code> to abort handling
	 */
	public boolean handleFlightEvent(SimulationStatus status, FlightEvent event) throws SimulationException;

	/**
	 * Motor ignition event.
	 * 
	 * @param status   the simulation status
	 * @param motorId  the motor id in the MotorInstanceConfiguration
	 * @param mount    the motor mount containing the motor
	 * @param instance the motor instance being ignited
	 * @return <code>true</code> to ignite the motor, <code>false</code> to abort
	 *         ignition
	 */
	public boolean motorIgnition(SimulationStatus status, MotorConfigurationId motorId, MotorMount mount,
			MotorClusterState instance) throws SimulationException;

	/**
	 * Recovery device deployment.
	 * 
	 * @param status         the simulation status
	 * @param recoveryDevice the recovery device that is being deployed.
	 * @return <code>true</code> to deploy the recovery device, <code>false</code>
	 *         to abort deployment
	 */
	public boolean recoveryDeviceDeployment(SimulationStatus status, RecoveryDevice recoveryDevice)
			throws SimulationException;

}
