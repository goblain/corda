package net.corda.client.jfx.model

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import net.corda.client.jfx.utils.LeftOuterJoinedMap
import net.corda.client.jfx.utils.flatten
import net.corda.client.jfx.utils.fold
import net.corda.client.jfx.utils.getObservableValues
import net.corda.client.jfx.utils.map
import net.corda.client.jfx.utils.recordAsAssociation
import net.corda.core.ErrorOr
import net.corda.core.flows.FlowInitiator
import net.corda.core.flows.StateMachineRunId
import net.corda.core.messaging.StateMachineInfo
import net.corda.core.messaging.StateMachineUpdate
import rx.Observable

data class ProgressTrackingEvent(val stateMachineId: StateMachineRunId, val message: String) {
    companion object {
        fun createStreamFromStateMachineInfo(stateMachine: StateMachineInfo): Observable<ProgressTrackingEvent>? {
            return stateMachine.progressTrackerStepAndUpdates?.let { pair ->
                val (current, future) = pair
                future.map { ProgressTrackingEvent(stateMachine.id, it) }.startWith(ProgressTrackingEvent(stateMachine.id, current))
            }
        }
    }
}

data class ProgressStatus(val status: String)

sealed class StateMachineStatus {
    data class Added(val stateMachineName: String, val flowInitiator: FlowInitiator) : StateMachineStatus()
    data class Removed(val result: ErrorOr<*>) : StateMachineStatus()
}

// TODO StateMachineData and StateMachineInfo
data class StateMachineData(
        val id: StateMachineRunId,
        val stateMachineName: String,
        val flowInitiator: FlowInitiator,
        val addRmStatus: ObservableValue<StateMachineStatus>,
        val stateMachineStatus: ObservableValue<ProgressStatus?>
)

class StateMachineDataModel {
    private val stateMachineUpdates by observable(NodeMonitorModel::stateMachineUpdates)
    private val progressTracking by observable(NodeMonitorModel::progressTracking)
    private val progressEvents = progressTracking.recordAsAssociation(ProgressTrackingEvent::stateMachineId)

    private val stateMachineStatus = stateMachineUpdates.fold(FXCollections.observableHashMap<StateMachineRunId, SimpleObjectProperty<StateMachineStatus>>()) { map, update ->
        when (update) {
            is StateMachineUpdate.Added -> {
                val flowInitiator= update.stateMachineInfo.initiator
                val added: SimpleObjectProperty<StateMachineStatus> =
                        SimpleObjectProperty(StateMachineStatus.Added(update.stateMachineInfo.flowLogicClassName, flowInitiator))
                map[update.id] = added
            }
            is StateMachineUpdate.Removed -> {
                val added = map[update.id]
                added ?: throw Exception("State machine removed with unknown id ${update.id}")
                added.set(StateMachineStatus.Removed(update.result))
            }
        }
    }

    val stateMachineDataList = LeftOuterJoinedMap(stateMachineStatus, progressEvents) { id, status, progress ->
        val smStatus = status.value as StateMachineStatus.Added // TODO not always added
        // todo easybind
        Bindings.createObjectBinding({
            StateMachineData(id, smStatus.stateMachineName, smStatus.flowInitiator, status, progress.map { it?.let { ProgressStatus(it.message) } })
        }, arrayOf(progress, status))
    }.getObservableValues().flatten()

    val stateMachinesInProgress = stateMachineDataList.filtered { it.addRmStatus.value !is StateMachineStatus.Removed }
    val stateMachinesDone = stateMachineDataList.filtered { it.addRmStatus.value is StateMachineStatus.Removed }
    val stateMachinesFinished = stateMachinesDone.filtered {
        val res = it.addRmStatus.value as StateMachineStatus.Removed
        res.result.error == null
    }
    val stateMachinesError = stateMachinesDone.filtered {
        val res = it.addRmStatus.value as StateMachineStatus.Removed
        res.result.error != null
    }
}
