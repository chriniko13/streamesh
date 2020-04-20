package io.scicast.streamesh.core.internal;

import io.scicast.streamesh.core.*;
import io.scicast.streamesh.core.flow.FlowInstance;
import io.scicast.streamesh.core.flow.execution.MicroPipeRuntimeNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryStreameshStore implements StreameshStore {

    private Map<String, Definition> definitions = new ConcurrentHashMap<>();
    private Map<String, Definition> definitionsByName = new ConcurrentHashMap<>();

    private Map<String, FlowInstance> flowInstances = new ConcurrentHashMap<>();
    private Map<String, Set<FlowInstance>> flowDefinitionsToInstances = new ConcurrentHashMap<>();

    private Map<String, TaskDescriptor> tasks = new ConcurrentHashMap<>();
    private Map<MicroPipe, Set<TaskDescriptor>> pipesToTasks = new ConcurrentHashMap<>();


    @Override
    public void storeDefinition(Definition definition) {
        Definition previous = definitionsByName.get(definition.getName());
        if(previous != null) {
            definitions.remove(previous.getId());
        }
        definitions.put(definition.getId(), definition);
        definitionsByName.put(definition.getName(), definition);
    }

    @Override
    public void storeFlowInstance(FlowInstance instance) {
        flowInstances.put(instance.getId(), instance);
        Set<FlowInstance> flowInstances = flowDefinitionsToInstances.get(instance.getDefinitionId());
        if (flowInstances == null) {
            flowInstances = new HashSet<>();
        }
        flowInstances.add(instance);
        flowDefinitionsToInstances.put(instance.getDefinitionId(), flowInstances);
    }

    @Override
    public FlowInstance getFlowInstance(String instanceId) {
        return flowInstances.get(instanceId);
    }

    @Override
    public Set<FlowInstance> getFlowInstancesByDefinition(String flowDefinitionId) {
        return flowDefinitionsToInstances.getOrDefault(flowDefinitionId, new HashSet<>()).stream().collect(Collectors.toSet());
    }

    @Override
    public Set<TaskDescriptor> getTasksByFlowInstance(String flowInstanceId) {
        return flowInstances.get(flowInstanceId).getExecutionGraph().getNodes().stream()
                .filter(node -> node instanceof MicroPipeRuntimeNode)
                .map(node -> (MicroPipeRuntimeNode) node)
                .map(node -> tasks.get(node.getTaskId()))
                .collect(Collectors.toSet());
    }

    @Override
    public Definition getDefinitionById(String id) {
        return definitions.get(id);
    }

    @Override
    public Definition getDefinitionByName(String name) {
        return definitionsByName.get(name);
    }

    @Override
    public void removeDefinition(String id) {
        Definition removed = definitions.remove(id);
        if(removed != null) {
            definitionsByName.remove(removed.getName());
        }
    }

    @Override
    public Set<Definition> getAllDefinitions() {
        return definitions.entrySet().stream()
                .map(e -> e.getValue())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<TaskDescriptor> getAllTasks() {
        return tasks.values().stream().collect(Collectors.toSet());
    }

    @Override
    public Set<TaskDescriptor> getTasksByDefinition(String definitionId) {
        return pipesToTasks.getOrDefault(getDefinitionById(definitionId), new HashSet<>()).stream().collect(Collectors.toSet());
    }

    @Override
    public TaskDescriptor getTaskById(String jobId) {
        return tasks.get(jobId);
    }

    @Override
    public void updateTask(String definitionId, TaskDescriptor descriptor) {
        tasks.put(descriptor.getId(), descriptor);
        Definition definition = getDefinitionById(definitionId);
        if (!(definition instanceof MicroPipe)) {
            throw new IllegalArgumentException("Cannot associate a task to a definition of type " + definition.getType());
        }
        Set<TaskDescriptor> taskDescriptors = pipesToTasks.get(definition);
        if (taskDescriptors == null) {
            taskDescriptors = new HashSet<>();
        } else {
            taskDescriptors.remove(descriptor);
            taskDescriptors.add(descriptor);
        }
        pipesToTasks.put((MicroPipe) definition, taskDescriptors);
    }

    @Override
    public Set<FlowInstance> getAllFlowInstances() {
        return flowInstances.values().stream().collect(Collectors.toSet());
    }

    @Override
    public void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    @Override
    public void removeFlowInstance(String flowInstanceId) {
        flowInstances.remove(flowInstanceId);
    }
}