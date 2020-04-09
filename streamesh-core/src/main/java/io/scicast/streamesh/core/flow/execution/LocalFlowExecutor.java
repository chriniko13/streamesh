package io.scicast.streamesh.core.flow.execution;

import io.scicast.streamesh.core.StreameshContext;
import io.scicast.streamesh.core.StreameshOrchestrator;
import io.scicast.streamesh.core.TaskDescriptor;
import io.scicast.streamesh.core.TaskExecutionEvent;
import io.scicast.streamesh.core.exception.InvalidCmdParameterException;
import io.scicast.streamesh.core.exception.MissingParameterException;
import io.scicast.streamesh.core.flow.FlowDefinition;
import io.scicast.streamesh.core.flow.FlowInstance;
import io.scicast.streamesh.core.flow.FlowParameter;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
@Getter
public class LocalFlowExecutor implements FlowExecutor {

    private final StreameshContext context;
    private String flowInstanceId;
    private Consumer<FlowExecutionEvent<?>> upstreamFlowHandler;

    @Override
    public FlowInstance execute(FlowDefinition flow, Map<?, ?> input, Consumer<FlowExecutionEvent<?>> eventHandler) {
        ExecutionGraph runtimeGraph = new ExecutionGraph(flow.getGraph());
        this.upstreamFlowHandler = eventHandler;

        flowInstanceId = UUID.randomUUID().toString();
        FlowInstance instance = FlowInstance.builder()
            .definitionId(flow.getId())
            .flowName(flow.getName())
            .id(flowInstanceId)
            .executionGraph(runtimeGraph)
            .build();
        context.getStore().storeFlowInstance(instance);
        init(runtimeGraph, input);
        context.getStore().storeFlowInstance(instance);
        return instance;
    }

    private void init(ExecutionGraph runtimeGraph, Map<?, ?> input) {
        runtimeGraph.getPipeInputNodes().forEach(node -> {
            if (node.isStaticallyInitialised()) {
                node.notifyObservers();
            }
        });

        Set<FlowParameterRuntimeNode> inputNodes = runtimeGraph.getInputNodes();
        inputNodes.forEach(node -> {
            FlowParameter parameterSpec = (FlowParameter) node.getStaticGraphNode().getValue();
            Object o = input.get(parameterSpec.getName());
            node.update(buildRuntimeDataValue(parameterSpec, o));
        });

        executeNodes(runtimeGraph.getExecutableNodes());

        checkFlowOutput(runtimeGraph);

    }

    private void checkFlowOutput(ExecutionGraph runtimeGraph) {
        Set<FlowOutputRuntimeNode> outputNodes = runtimeGraph.getOutputNodes();
        outputNodes.forEach(node -> {
            if (node.getValue() != null && !node.isOutputAlreadyConsumed()) {
                FlowExecutionEvent<?> event = FlowExecutionEvent.builder()
                        .type(FlowExecutionEvent.EventType.OUTPUT_AVAILABILITY)
                        .descriptor(OutputAvailabilityDescriptor.builder()
                            .flowInstanceId(flowInstanceId)
                            .nodeName(node.getName())
                            .runtimeDataValue(node.getValue())
                            .build())
                        .build();
                upstreamFlowHandler.accept(event);
            }
        });
    }

    private void executeNodes(Set<ExecutablePipeRuntimeNode> executableNodes) {
        StreameshOrchestrator orchestrator = context.getOrchestrator();
        executableNodes.forEach(node -> {
            if (node instanceof MicroPipeRuntimeNode) {
                TaskDescriptor descriptor = orchestrator.scheduleTask(node.getDefinitionId(), node.getPipeInput(), this::onTaskExecutionEvent);
                ((MicroPipeRuntimeNode) node).setTaskId(descriptor.getId());
            } else if (node instanceof FlowReferenceRuntimeNode){
                FlowInstance instance = orchestrator.scheduleFlow(node.getDefinitionId(), node.getPipeInput(), this::onFlowExecutionEvent);
                ((FlowReferenceRuntimeNode) node).setInstanceId(instance.getId());
            }
        });
    }

    private void onFlowExecutionEvent(FlowExecutionEvent<?> flowExecutionEvent) {

    }

    private void onTaskExecutionEvent(TaskExecutionEvent<?> taskExecutionEvent) {

    }

    private RuntimeDataValue buildRuntimeDataValue(FlowParameter parameterSpec, Object o) {
        if (!parameterSpec.isOptional() && o == null) {
            throw new MissingParameterException(String.format("Parameter %s is mandatory.", parameterSpec.getName()));
        }
        if (parameterSpec.isRepeatable() && (!List.class.isAssignableFrom(o.getClass()))) {
            throw new InvalidCmdParameterException(String.format("Parameter %s must be provided as an array", parameterSpec.getName()));
        }

        RuntimeDataValue.RuntimeDataValueBuilder builder = RuntimeDataValue.builder();
        Set<RuntimeDataValue.RuntimeDataValuePart> parts;
        if (!parameterSpec.isRepeatable()) {
            parts = Stream.of(RuntimeDataValue.RuntimeDataValuePart.builder()
                    .state(RuntimeDataValue.DataState.COMPLETE)
                    .refName(parameterSpec.getName())
                    .value((String) o)
                    .build())
                .collect(Collectors.toSet());
        } else {
            parts = ((List<String>) o).stream()
                    .map(s -> RuntimeDataValue.RuntimeDataValuePart.builder()
                        .value(s)
                        .refName(parameterSpec.getName())
                        .state(RuntimeDataValue.DataState.COMPLETE)
                        .build())
                    .collect(Collectors.toSet());
        }
        return builder.parts(parts)
                .build();
    }

}
