/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.unomi.graphql.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Charsets;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.TypeResolutionEnvironment;
import graphql.execution.instrumentation.tracing.TracingInstrumentation;
import graphql.introspection.IntrospectionQuery;
import graphql.scalars.ExtendedScalars;
import graphql.schema.*;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Metadata;
import org.apache.unomi.api.PartialList;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.query.Query;
import org.apache.unomi.api.segments.Segment;
import org.apache.unomi.api.services.DefinitionsService;
import org.apache.unomi.api.services.EventService;
import org.apache.unomi.api.services.SegmentService;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component(
        service={javax.servlet.http.HttpServlet.class,javax.servlet.Servlet.class},
        property = {"alias=/sdlgraphql", "jmx.objectname=graphql.servlet:type=graphql"}
)
public class CDPSDLServletImpl extends HttpServlet {

    private BundleContext bundleContext;
    private ObjectMapper objectMapper;
    private GraphQL graphQL;

    private EventService eventService;
    private DefinitionsService definitionsService;
    private SegmentService segmentService;


    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Reference
    public void setEventService(EventService eventService) {
        this.eventService = eventService;
    }

    @Reference
    public void setDefinitionService(DefinitionsService definitionService) {
        this.definitionsService = definitionService;
    }

    @Reference
    public void setSegmentService(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    RuntimeWiring buildRuntimeWiring() {

        GraphQLScalarType emptyTypeWorkAroundScalarType = GraphQLScalarType.newScalar()
                .name("EmptyTypeWorkAround")
                .description("A marker type to get around the limitation of GraphQL that doesn't allow empty types. It should be always ignored.")
                .coercing(new Coercing() {
                    @Override
                    public Object serialize(Object dataFetcherResult) throws CoercingSerializeException {
                        return null;
                    }

                    @Override
                    public Object parseValue(Object input) throws CoercingParseValueException {
                        return input;
                    }

                    @Override
                    public Object parseLiteral(Object input) throws CoercingParseLiteralException {
                        return input;
                    }
                })
                .build();

        GraphQLScalarType geopointScalarType = GraphQLScalarType.newScalar()
                .name("GeoPoint")
                .description("A type that represents a geographical location")
                .coercing(new Coercing() {
                    @Override
                    public Object serialize(Object dataFetcherResult) throws CoercingSerializeException {
                        return null;
                    }

                    @Override
                    public Object parseValue(Object input) throws CoercingParseValueException {
                        return input;
                    }

                    @Override
                    public Object parseLiteral(Object input) throws CoercingParseLiteralException {
                        return input;
                    }
                })
                .build();

        return RuntimeWiring.newRuntimeWiring()
                .scalar(ExtendedScalars.DateTime)
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.Time)
                .scalar(emptyTypeWorkAroundScalarType)
                .scalar(geopointScalarType)
                .type("CDP_EventInterface", typeWiring -> typeWiring
                        .typeResolver(new TypeResolver() {
                            @Override
                            public GraphQLObjectType getType(TypeResolutionEnvironment env) {
                                Map<String,Object> object = env.getObject();
                                String unomiEventType = (String) object.get("__unomiEventType");
                                if ("view".equals(unomiEventType)) {
                                    return env.getSchema().getObjectType("Unomi_PageViewEvent");
                                } else if ("sessionCreated".equals(unomiEventType)) {
                                    return env.getSchema().getObjectType("Unomi_SessionCreatedEvent");
                                } else {
                                    return env.getSchema().getObjectType("Unomi_UnknownEvent");
                                }
                            }
                        }))
                .type("CDP_ProfileInterface", typeWiring -> typeWiring
                        .typeResolver(new TypeResolver() {
                            @Override
                            public GraphQLObjectType getType(TypeResolutionEnvironment env) {
                                return null;
                            }
                        }))
                .type("CDP_PropertyInterface", typeWiring -> typeWiring
                        .typeResolver(new TypeResolver() {
                            @Override
                            public GraphQLObjectType getType(TypeResolutionEnvironment env) {
                                return null;
                            }
                        }))
                .type("Query", typeWiring -> typeWiring.dataFetcher("cdp", dataFetchingEnvironment -> "CDP"))
                .type("CDP_Query", typeWiring -> typeWiring
                        .dataFetcher("findEvents", dataFetchingEnvironment -> {
                    Map<String,Object> arguments = dataFetchingEnvironment.getArguments();
                    Integer size = (Integer) arguments.get("first");
                    if (size == null) {
                        size = 10;
                    }
                    String after = (String) arguments.get("after");
                    if (after == null) {
                        after = "0";
                    }
                    int offset = Integer.parseInt(after);
                    Object filter = arguments.get("filter");
                    Condition condition = eventFilter2Condition(filter);
                    PartialList<Event> events = eventService.searchEvents(condition, offset, size);
                    Map<String,Object> eventConnection = new HashMap<>();
                    List<Map<String,Object>> eventEdges = new ArrayList<>();
                    for (Event event : events.getList()) {
                        Map<String,Object> eventEdge = new HashMap<>();
                        Map<String,Object> eventNode = new HashMap<>();
                        eventNode.put("id", event.getItemId());
                        eventNode.put("__unomiEventType", event.getEventType());
                        eventNode.put("cdp_profileID", getCDPProfileID(event.getProfileId()));
                        eventEdge.put("node", eventNode);
                        eventEdge.put("cursor", event.getItemId());
                        eventEdges.add(eventEdge);
                    }
                    eventConnection.put("edges", eventEdges);
                    Map<String,Object> pageInfo = new HashMap<>();
                    pageInfo.put("hasPreviousPage", false);
                    pageInfo.put("hasNextPage", events.getTotalSize() > events.getList().size());
                    eventConnection.put("pageInfo", pageInfo);
                    return eventConnection;
                })
                .dataFetcher("findSegments", dataFetchingEnvironment -> {
                    Map<String,Object> arguments = dataFetchingEnvironment.getArguments();
                    Integer size = (Integer) arguments.get("first");
                    if (size == null) {
                        size = 10;
                    }
                    String after = (String) arguments.get("after");
                    if (after == null) {
                        after = "0";
                    }
                    int offset = Integer.parseInt(after);
                    Object filter = arguments.get("filter");
                    Condition condition = eventFilter2Condition(filter);

                    Map<String,Object> segmentConnection = new HashMap<>();
                    Query query = new Query();
                    query.setCondition(condition);
                    query.setLimit(size);
                    query.setOffset(offset);
                    // query.setSortby(sortBy);
                    PartialList<Metadata> segmentMetadatas = segmentService.getSegmentMetadatas(query);
                    List<Map<String,Object>> segmentEdges = new ArrayList<>();
                    for (Metadata segmentMetadata : segmentMetadatas.getList()) {
                        Map<String,Object> segment = new HashMap<>();
                        segment.put("id", segmentMetadata.getId());
                        segment.put("name", segmentMetadata.getName());
                        Map<String,Object> segmentView = new HashMap<>();
                        segmentView.put("name", segmentMetadata.getScope());
                        segment.put("view", segmentView);
                        Segment unomiSegment = segmentService.getSegmentDefinition(segmentMetadata.getId());
                        Condition segmentCondition = unomiSegment.getCondition();
                        segment.put("profiles", segmentConditionToProfileFilter(segmentCondition));
                        Map<String,Object> segmentEdge = new HashMap<>();
                        segmentEdge.put("node", segment);
                        segmentEdge.put("cursor", segmentMetadata.getId());
                        segmentEdges.add(segmentEdge);
                    }
                    segmentConnection.put("edges", segmentEdges);
                    Map<String,Object> pageInfo = new HashMap<>();
                    pageInfo.put("hasPreviousPage", false);
                    pageInfo.put("hasNextPage", segmentMetadatas.getTotalSize() > segmentMetadatas.getList().size());
                    segmentConnection.put("pageInfo", pageInfo);
                    return segmentConnection;
                }))
                .build();
    }

    private Map<String, Object> segmentConditionToProfileFilter(Condition segmentCondition) {
        Map<String,Object> profileFilter = new HashMap<>();
        // profileFilter.put("profileIDs", new ArrayList<String>());
        return profileFilter;
    }

    private Map<String,Object> getCDPProfileID(String profileId) {
        Map<String,Object> cdpProfileID = new HashMap<>();
        Map<String,Object> client = getCDPClient(profileId);
        cdpProfileID.put("client", client);
        cdpProfileID.put("id", profileId);
        cdpProfileID.put("uri", "cdp_profile:" + client.get("id") + "/" + profileId);
        return cdpProfileID;
    }

    private Map<String,Object> getCDPClient(String profileId) {
        Map<String,Object> cdpClient = new HashMap<>();
        cdpClient.put("id", "unomi");
        cdpClient.put("title", "Default Unomi client");
        return cdpClient;
    }

    private Condition eventFilter2Condition(Object filter) {
        // todo implement transformation to proper event conditions
        Condition matchAllCondition = new Condition(definitionsService.getConditionType("matchAllCondition"));
        return matchAllCondition;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        SchemaParser schemaParser = new SchemaParser();
        SchemaGenerator schemaGenerator = new SchemaGenerator();

        Reader cdpSchemaReader = getSchemaReader("cdp-schema.graphqls");
        Reader unomiSchemaReader = getSchemaReader("unomi-schema.graphqls");
        //File schemaFile2 = loadSchema("cdp-schema.graphqls");
        //File schemaFile3 = loadSchema("cdp-schema.graphqls");

        TypeDefinitionRegistry typeRegistry = new TypeDefinitionRegistry();

        // each registry is merged into the main registry
        typeRegistry.merge(schemaParser.parse(cdpSchemaReader));
        typeRegistry.merge(schemaParser.parse(unomiSchemaReader));
        //typeRegistry.merge(schemaParser.parse(schemaFile2));
        //typeRegistry.merge(schemaParser.parse(schemaFile3));

        RuntimeWiring wiring = buildRuntimeWiring();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeRegistry, wiring);
        graphQL = GraphQL.newGraphQL(graphQLSchema)
                .instrumentation(new TracingInstrumentation())
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String query = req.getParameter("query");
        if ("/schema.json".equals(req.getPathInfo())) {
            query = IntrospectionQuery.INTROSPECTION_QUERY;
        }
        String operationName = req.getParameter("operationName");
        String variableStr = req.getParameter("variables");
        Map<String, Object> variables = new HashMap<>();
        if ((variableStr != null) && (variableStr.trim().length() > 0)) {
            TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {
            };
            variables = objectMapper.readValue(variableStr, typeRef);
        }

        setupCORSHeaders(req, resp);
        executeGraphQLRequest(resp, query, operationName, variables);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        InputStream bodyStream = req.getInputStream();
        TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {
        };
        Map<String, Object> body = objectMapper.readValue(bodyStream, typeRef);
        String query = (String) body.get("query");
        String operationName = (String) body.get("operationName");
        Map<String, Object> variables = (Map<String, Object>) body.get("variables");
        if (variables == null) {
            variables = new HashMap<>();
        }

        setupCORSHeaders(req, resp);
        executeGraphQLRequest(resp, query, operationName, variables);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        setupCORSHeaders(req, resp);
        resp.flushBuffer();
    }

    private void executeGraphQLRequest(HttpServletResponse resp, String query, String operationName, Map<String, Object> variables) throws IOException {
        if (query == null || query.trim().length() == 0) {
            throw new RuntimeException("Query cannot be empty or null");
        }
        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables)
                .operationName(operationName)
                .build();

        ExecutionResult executionResult = graphQL.execute(executionInput);

        Map<String, Object> toSpecificationResult = executionResult.toSpecification();
        PrintWriter out = resp.getWriter();
        objectMapper.writeValue(out, toSpecificationResult);
    }

    private Reader getSchemaReader(String resourceUrl) {
        try {
            return new InputStreamReader(bundleContext.getBundle().getResource(resourceUrl).openConnection().getInputStream(), Charsets.UTF_8.name());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Setup CORS headers as soon as possible so that errors are not misconstrued on the client for CORS errors
     *
     * @param httpServletRequest
     * @param response
     * @throws IOException
     */
    public void setupCORSHeaders(HttpServletRequest httpServletRequest, ServletResponse response) throws IOException {
        if (response instanceof HttpServletResponse) {
            // todo this should be configurable
            HttpServletResponse httpServletResponse = (HttpServletResponse) response;
            if (httpServletRequest != null && httpServletRequest.getHeader("Origin") != null) {
                httpServletResponse.setHeader("Access-Control-Allow-Origin", httpServletRequest.getHeader("Origin"));
            } else {
                httpServletResponse.setHeader("Access-Control-Allow-Origin", "*");
            }
            httpServletResponse.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, X-Apollo-Tracing, test");
            httpServletResponse.setHeader("Access-Control-Allow-Credentials", "true");
            httpServletResponse.setHeader("Access-Control-Allow-Methods", "OPTIONS, POST, GET");
            // httpServletResponse.setHeader("Access-Control-Max-Age", "600");
            // httpServletResponse.setHeader("Access-Control-Expose-Headers","Access-Control-Allow-Origin");
            // httpServletResponse.flushBuffer();
        }
    }

}