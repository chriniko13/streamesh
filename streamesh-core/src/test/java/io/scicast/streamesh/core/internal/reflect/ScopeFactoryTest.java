package io.scicast.streamesh.core.internal.reflect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.scicast.streamesh.core.Micropipe;
import io.scicast.streamesh.core.StreameshContext;
import io.scicast.streamesh.core.StreameshStore;
import io.scicast.streamesh.core.flow.FlowDefinition;
import io.scicast.streamesh.core.flow.FlowGraph;
import io.scicast.streamesh.core.flow.FlowGraphBuilder;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScopeFactoryTest {

    public static final String MERGER_NAME = "http-data-merger";
    public static final String PLOTTER_NAME = "python-plotter";
    public static final String DOWNLOADER_NAME = "s3-downloader";
    public static final String DB_READER_NAME = "simple-db-reader";
    private static final String MICROPIPES_PATH = "/micropipes/";

    private static StreameshStore streameshStore;
    private static Micropipe merger;
    private static Micropipe plotter;
    private static Micropipe s3Downloader;
    private static Micropipe dbReader;
    private static ObjectMapper mapper = new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private StreameshContext context;

    private ObjectMapper jsonMapper = new ObjectMapper();

    @BeforeClass
    public static void setUpClass() throws IOException {
        merger = loadDefinition(MICROPIPES_PATH + "http-data-merger.yml", Micropipe.class);
        plotter = loadDefinition(MICROPIPES_PATH + "python-plotter.yml", Micropipe.class);
        s3Downloader = loadDefinition(MICROPIPES_PATH + "s3-downloader.yml", Micropipe.class);
        dbReader = loadDefinition(MICROPIPES_PATH + "simple-db-reader.yml", Micropipe.class);

        streameshStore = mock(StreameshStore.class);
        when(streameshStore.getDefinitionByName(MERGER_NAME)).thenReturn(merger);
        when(streameshStore.getDefinitionByName(PLOTTER_NAME)).thenReturn(plotter);
        when(streameshStore.getDefinitionByName(DOWNLOADER_NAME)).thenReturn(s3Downloader);
        when(streameshStore.getDefinitionByName(DB_READER_NAME)).thenReturn(dbReader);
    }

    @Before
    public void setUp() {
        context = StreameshContext.builder()
                .store(streameshStore)
                .build();
    }

    @Test
    public void testScopeCreation() throws IOException {
        FlowDefinition definition = loadDefinition("/flows/airbnb-flow.yml", FlowDefinition.class);
        ScopeFactory factory = ScopeFactory.builder()
                .streameshContext(context)
                .build();
        Scope scope = factory.create(definition);
        FlowGraph graph = new FlowGraphBuilder().build(scope);

//        graph.getNodes().forEach(System.out::println);

        List<String> path = Arrays.asList("s3-others", "type", "input", "bucket");
        Scope subScope = scope.subScope(path);

        List<String> pathByValue = scope.getPathByValue(subScope.getValue());

        pathByValue.forEach(System.out::println);

//        explainScope(scope, new ArrayList<>());

//        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT)
//                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
//                .writerFor(Scope.class).writeValue(System.out, scope);

    }

    @Test
    @Ignore
    public void testScopeForSubFlow() throws IOException {
        FlowDefinition airbnb = loadDefinition("/flows/airbnb-flow.yml", FlowDefinition.class);
        when(streameshStore.getDefinitionByName("airbnb-ny-properties")).thenReturn(airbnb);

        FlowDefinition definition = loadDefinition("/flows/recursive-airbnb-flow.yml", FlowDefinition.class);
        ScopeFactory factory = ScopeFactory.builder()
                .streameshContext(context)
                .build();

        Scope scope = factory.create(definition);
        FlowGraph graph = new FlowGraphBuilder().build(scope);


        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .writerFor(Scope.class).writeValue(System.out, scope);


    }

    private void explainScope(Scope currentScope, List<String> basePath) {
        String stringifiedBasePath = basePath.stream().collect(Collectors.joining("/"));
        Object value = currentScope.getValue();
        if (value == null) {
            System.out.println("/" + stringifiedBasePath + " is null.");
        } else if (value instanceof  String) {
            System.out.println("/" + stringifiedBasePath + " is " + value.toString());
        } else {
            System.out.println("/" + stringifiedBasePath + " is a " + value.getClass().getSimpleName() + "[" + value.hashCode() + "]");
        }

        currentScope.getStructure().entrySet().forEach(entry -> {
            explainScope(entry.getValue(),
                    Stream.concat(basePath.stream(), Stream.of(entry.getKey()))
                            .collect(Collectors.toList()));
        });
    }

    private static <T> T loadDefinition(String resource, Class<T> clazz) throws IOException {
        return mapper.reader().forType(clazz).readValue(ScopeFactoryTest.class.getResource(resource));
    }

}
