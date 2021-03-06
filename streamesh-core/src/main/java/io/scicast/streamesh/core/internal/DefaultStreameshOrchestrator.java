package io.scicast.streamesh.core.internal;

import io.scicast.streamesh.core.*;
import io.scicast.streamesh.core.crypto.CryptoUtil;
import io.scicast.streamesh.core.exception.InvalidCmdParameterException;
import io.scicast.streamesh.core.exception.MissingParameterException;
import io.scicast.streamesh.core.exception.NotFoundException;
import io.scicast.streamesh.core.flow.*;
import io.scicast.streamesh.core.flow.execution.*;
import io.scicast.streamesh.core.internal.reflect.Scope;
import io.scicast.streamesh.core.internal.reflect.ScopeFactory;

import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DefaultStreameshOrchestrator implements StreameshOrchestrator {

    private static final String BASE_API_PATH = "/api/v1";
    private static final String TASKS_PATH = "/tasks/";

    private static final String STREAMESH_SERVER_HOST_NAME = "streamesh-server";
    private static final int PORT = 8080;

    private final StreameshStore streameshStore = new InMemoryStreameshStore();
    private Logger logger = Logger.getLogger(this.getClass().getSimpleName());
    private final StreameshContext context;
    private final ScopeFactory scopeFactory;

    private OrchestrationDriver driver;

    public DefaultStreameshOrchestrator(String serverIpAddress) {
        ServiceLoader<OrchestrationDriver> loader = ServiceLoader.load(OrchestrationDriver.class);

       driver = StreamSupport.stream(loader.spliterator(), false)
                .peek(impl -> logger.info(
                        "Found orchestration driver of type " + impl.getClass().getSimpleName()))
                .findFirst()
                .map(impl -> {
                    logger.info("Using orchestration driver " + impl.getClass().getSimpleName());
                    return impl;
                })
                .orElseThrow(() -> new RuntimeException("No orchestration driver. Booting sequence aborted."));

        context = StreameshContext.builder()
                .orchestrationDriver(driver)
                .store(streameshStore)
                .orchestrator(this)
                .serverInfo(StreameshServerInfo.builder()
                        .host(STREAMESH_SERVER_HOST_NAME)
                        .ipAddress(serverIpAddress)
                        .port(PORT)
                        .baseApiPath(BASE_API_PATH)
                        .protocol(StreameshServerInfo.WebProtocol.http)
                        .build())
                .build();

        scopeFactory = ScopeFactory.builder()
                .streameshContext(context)
                .build();
    }

    public String applyDefinition(Definition definition) {
        if (definition instanceof MicroPipe) {
            return applyMicroPipe((MicroPipe) definition);
        } else if (definition instanceof FlowDefinition){
            return applyFlowDefinition((FlowDefinition) definition);
        } else {
            throw new NotFoundException("Unrecognized definition type " + definition.getType());
        }
    }

    private String applyFlowDefinition(FlowDefinition definition) {
        String definitionId = UUID.randomUUID().toString();
        Scope scope = scopeFactory.create(definition);
        FlowGraph graph = new FlowGraphBuilder().build(scope);

        streameshStore.storeDefinition(definition.withId(definitionId)
            .withGraph(graph)
            .withScope(scope));
        return definitionId;
    }



    private String applyMicroPipe(MicroPipe micropipe) {
        String imageId = driver.retrieveContainerImage(micropipe.getImage());
        String definitionId = UUID.randomUUID().toString();
        streameshStore.storeDefinition(micropipe.withImageId(imageId)
                .withId(definitionId));
        return definitionId;
    }

    public Definition getDefinition(String id) {
        Definition definition = streameshStore.getDefinitionById(id);
        if(definition == null) {
            throw new NotFoundException(String.format("No definition with id %s found", id));
        }
        return definition;
    }

    public Definition getDefinitionByName(String name) {
        Definition definition = streameshStore.getDefinitionByName(name);
        if(definition == null) {
            throw new NotFoundException(String.format("No definition found for name %s", name));
        }
        return definition;
    }

    public void removeDefinition(String id) {
        Definition definition = streameshStore.getDefinitionById(id);
        if (definition == null) {
            throw new NotFoundException("Could not find a definition with id " + id);
        }
        verifyNoDependingDefinitions(definition);
        if (definition instanceof MicroPipe) {
            Set<TaskDescriptor> tasksByDefinition = streameshStore.getTasksByDefinition(id);
            for (TaskDescriptor task : tasksByDefinition) {
                this.killTask(task.getId());
                streameshStore.removeTask(task.getId());
            }
        } else {
            Set<FlowInstance> flowInstancesByDefinition = streameshStore.getFlowInstancesByDefinition(id);
            for (FlowInstance instance : flowInstancesByDefinition) {
                try {
                    this.killFlowInstance(instance.getId());
                    streameshStore.getTasksByFlowInstance(instance.getId()).stream()
                            .forEach(task -> streameshStore.removeTask(task.getId()));
                    streameshStore.removeFlowInstance(instance.getId());
                } catch (NotFoundException nfe) {
                    logger.info(String.format("Flow instance %s has already been deleted.", instance.getId()));
                }
            }

        }
        streameshStore.removeDefinition(id);
    }

    private void verifyNoDependingDefinitions(Definition definition) {
        Set<FlowDefinition> allFlows = streameshStore.getAllDefinitions().stream()
                .filter(d -> d instanceof FlowDefinition)
                .map(d -> (FlowDefinition) d)
                .collect(Collectors.toSet());
        Set<String> dependingDefinitions = allFlows.stream()
                .filter(flow -> !flow.getGraph().getNodes().stream()
                        .filter(node -> {
                            Object nodeValue = node.getValue();
                            boolean dependant = nodeValue instanceof MicroPipe
                                    && definition.getId().equals(((MicroPipe) nodeValue).getId());
                            dependant = dependant || (nodeValue instanceof FlowReference
                                    && definition.getId().equals(((FlowReference) nodeValue).getDefinition().getId()));
                            return dependant;
                        }).collect(Collectors.toSet()).isEmpty())
                .map(flow -> flow.getName())
                .collect(Collectors.toSet());
        if (!dependingDefinitions.isEmpty()) {
            throw new IllegalStateException(String.format("Cannot remove service %s. The following services depend on it: \n%s." +
                            "\nRemove them first.",
                    definition.getName(),
                    dependingDefinitions.stream()
                            .map(name -> "- " + name)
                            .collect(Collectors.joining("\n"))));
        }
     }

    public Set<Definition> getDefinitions() {
        return streameshStore.getAllDefinitions();
    }

    public Set<TaskDescriptor> getAllTasks() {
        return streameshStore.getAllTasks();
    }

    public Set<TaskDescriptor> getTasksByDefinition(String definitionId) {
        return streameshStore.getTasksByDefinition(definitionId);
    }

    public Set<TaskDescriptor> getTasksByFlowInstanceId(String flowInstanceId) {
        return streameshStore.getTasksByFlowInstance(flowInstanceId);
    }


    public TaskDescriptor scheduleTask(String definitionId, Map<?, ?> input) {
        return scheduleTask(definitionId, input, event -> {});
    }

    public TaskDescriptor scheduleTask(String definitionId, Map<?, ?> input, Consumer<TaskExecutionEvent<?>> eventHandler) {
        return scheduleTask(definitionId, UUID.randomUUID().toString(), input, eventHandler);
    }

    public TaskDescriptor scheduleTask(String definitionId, String taskId, Map<?, ?> input, Consumer<TaskExecutionEvent<?>> eventHandler) {
        Definition definition = getDefinition(definitionId);
        if (!(definition instanceof MicroPipe)) {
            throw new IllegalArgumentException("Cannot schedule tasks for definitions of type " + definition.getType());
        }
        MicroPipe pipe = (MicroPipe) definition;
        validateTaskInput(input, pipe.getInputMapping());
        TaskDescriptor descriptor = driver.scheduleTask(
                TaskExecutionIntent.builder()
                    .image(pipe.getImage())
                    .taskId(taskId)
                    .taskInput(pipe.getInputMapping())
                    .taskOutputs(pipe.getOutputMapping())
                    .runtimeInput(input)
                    .build(),
                event -> {
                    updateState(pipe, event);
                    eventHandler.accept(event);
                },
                context)
                .withServiceName(definition.getName())
                .withServiceId(definition.getId());
        updateIndexes(pipe, descriptor);
        return descriptor;
    }

    @Override
    public void killTask(String taskId) {
        driver.killTask(taskId, context);
    }

    @Override
    public void killFlowInstance(String flowInstanceId) {
        FlowInstance flowInstance = streameshStore.getFlowInstance(flowInstanceId);
        if (flowInstance == null) {
            throw new NotFoundException("Could not find a flow instance with id " + flowInstanceId);
        }
        flowInstance.getExecutionGraph().getNodes().stream()
                .filter(node -> node instanceof ExecutablePipeRuntimeNode)
                .forEach(node -> {
                    if (node instanceof MicroPipeRuntimeNode) {
                        String taskId = ((MicroPipeRuntimeNode) node).getTaskId();
                        if (taskId != null) {
                            killTask(taskId);
                        }
                    } else {
                        killFlowInstance(((FlowReferenceRuntimeNode)node).getInstanceId());
                    }
                });
        streameshStore.storeFlowInstance(flowInstance.withStatus(FlowInstance.FlowInstanceStatus.KILLED));
    }

    public FlowInstance scheduleFlow(String definitionId, Map<?, ?> input) {
        return scheduleFlow(definitionId, input, event -> {});
    }

    public FlowInstance scheduleFlow(String definitionId, Map<?, ?> input, Consumer<FlowExecutionEvent<?>> eventHandler) {
        return scheduleFlow(definitionId, UUID.randomUUID().toString(), input, eventHandler);
    }

    public FlowInstance scheduleFlow(String definitionId, String flowInstanceId, Map<?, ?> input, Consumer<FlowExecutionEvent<?>> eventHandler) {
        Definition definition = getDefinition(definitionId);
        if (!(definition instanceof FlowDefinition)) {
            throw new IllegalArgumentException("Cannot schedule flows for definitions of type " + definition.getType());
        }
        return new LocalFlowExecutor(context).execute((FlowDefinition) definition, flowInstanceId, input, eventHandler);
    }

    public TaskDescriptor scheduleSecureTask(String definitionId, Map<?, ?> input, String publicKey) {
        CryptoUtil.WrappedAesGCMKey wrappedKey = CryptoUtil.createWrappedKey(publicKey);
        TaskDescriptor descriptor = scheduleTask(definitionId, input);
        descriptor.setKey(wrappedKey);
        return descriptor;
    }

    public TaskDescriptor getTask(String taskId) {
        TaskDescriptor task = streameshStore.getTaskById(taskId);
        if (task == null) {
            throw new NotFoundException(String.format("No task found for id %s", taskId));
        }
        return task;
    }

    private void updateState(MicroPipe definition, TaskExecutionEvent<?> event) {
        if (event.getType().equals(TaskExecutionEvent.EventType.CONTAINER_STATE_CHANGE)) {
            TaskDescriptor descriptor = (TaskDescriptor) event.getDescriptor();
            updateIndexes(definition, descriptor);
        }
    }

    private void updateIndexes(MicroPipe definition, TaskDescriptor descriptor) {
        streameshStore.updateTask(definition.getId(),
                descriptor.withServiceId(definition.getId())
                        .withServiceName(definition.getName()));
    }

    private void validateTaskInput(Map<?, ?> input, TaskInput inputMapping) {
        inputMapping.getParameters().stream()
                .forEach(p -> {
                    Object o = input.get(p.getName());
                    if (!p.isOptional() && o == null) {
                        throw new MissingParameterException(String.format("Parameter %s is mandatory.", p.getName()));
                    }
                    if (p.isRepeatable() && (!List.class.isAssignableFrom(o.getClass()))) {
                        throw new InvalidCmdParameterException(String.format("Parameter %s must be provided as an array", p.getName()));
                    }
                });
    }

    public InputStream getTaskOutput(String taskDescriptorId, String outputName) {
        TaskDescriptor job = getTask(taskDescriptorId);
        InputStream stream = driver.getTaskOutput(taskDescriptorId, outputName);
        if (job.getKey() != null) {
            stream = CryptoUtil.getCipherInputStream(stream, job.getKey());
        }
        return stream;
    }

    @Override
    public InputStream getFlowOutput(String flowInstanceId, String outputName) {
        FlowInstance instance = getFlowInstance(flowInstanceId);
        FlowOutputRuntimeNode outputNode = instance.getExecutionGraph().getOutputNodes().stream()
                .filter(node -> outputName.equals(((FlowOutput) node.getStaticGraphNode().getValue()).getName()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(String.format("Cannot find output %s for the specified flow.", outputName)));

        if (outputNode.getValue() != null) {
            String value = outputNode.getValue().getParts().stream()
                    .findFirst()
                    .map(part -> part.getValue())
                    .orElse(null);
            if (value != null) {
                value = value.substring(value.indexOf(TASKS_PATH) + TASKS_PATH.length());
                String[] parameters = value.split("/");
                if (parameters.length == 2) {
                    return getTaskOutput(parameters[0], parameters[1]);
                }

            }
        }
        throw new NotFoundException(String.format("Flow output %s for `flow instance %s is not available.", outputName, flowInstanceId));
    }

    @Override
    public Set<FlowInstance> getAllFlowInstances() {
        return streameshStore.getAllFlowInstances();
    }

    @Override
    public FlowInstance getFlowInstance(String flowInstanceId) {
        return streameshStore.getFlowInstance(flowInstanceId);
    }
}
