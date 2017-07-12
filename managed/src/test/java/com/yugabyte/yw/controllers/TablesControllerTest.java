// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.inject.Bindings.bind;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.common.collect.ImmutableSet;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.FakeApiHelper;
import com.yugabyte.yw.forms.TableDefinitionTaskParams;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.helpers.ColumnDetails;
import com.yugabyte.yw.models.helpers.TaskType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.ColumnSchema;
import org.yb.Common.TableType;
import org.yb.Schema;
import org.yb.Type;
import org.yb.client.GetTableSchemaResponse;
import org.yb.client.ListTablesResponse;
import org.yb.client.YBClient;
import org.yb.master.Master;
import org.yb.master.Master.ListTablesResponsePB.TableInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;

import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.libs.Json;
import play.mvc.Result;
import play.test.Helpers;
import play.test.WithApplication;

public class TablesControllerTest extends WithApplication {
  public static final Logger LOG = LoggerFactory.getLogger(TablesControllerTest.class);
  private YBClientService mockService;
  private TablesController tablesController;
  private YBClient mockClient;
  private ListTablesResponse mockListTablesResponse;
  private GetTableSchemaResponse mockSchemaResponse;
  private Commissioner mockCommissioner;

  @Override
  protected Application provideApplication() {
    mockCommissioner = mock(Commissioner.class);
    return new GuiceApplicationBuilder()
        .configure((Map) Helpers.inMemoryDatabase())
        .overrides(bind(Commissioner.class).toInstance(mockCommissioner))
        .build();
  }

  @Before
  public void setUp() throws Exception {
    mockClient = mock(YBClient.class);
    mockService = mock(YBClientService.class);
    mockListTablesResponse = mock(ListTablesResponse.class);
    mockSchemaResponse = mock(GetTableSchemaResponse.class);
    when(mockService.getClient(any(String.class))).thenReturn(mockClient);
    tablesController = new TablesController(mockService);
  }

  @Test
  public void testListTablesFromYbClient() throws Exception {
    List<TableInfo> tableInfoList = new ArrayList<TableInfo>();
    Set<String> tableNames = new HashSet<String>();
    tableNames.add("Table1");
    tableNames.add("Table2");
    TableInfo ti1 = TableInfo.newBuilder()
                             .setName("Table1").setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default"))
                             .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                             .setTableType(TableType.REDIS_TABLE_TYPE)
                             .build();
    TableInfo ti2 = TableInfo.newBuilder()
                             .setName("Table2").setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default"))
                             .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
                             .setTableType(TableType.YQL_TABLE_TYPE)
                             .build();
    // Create System type table, this will not be returned in response
    TableInfo ti3 = TableInfo.newBuilder()
      .setName("Table3").setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("system"))
      .setId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
      .setTableType(TableType.YQL_TABLE_TYPE)
      .build();
    tableInfoList.add(ti1);
    tableInfoList.add(ti2);
    tableInfoList.add(ti3);
    when(mockListTablesResponse.getTableInfoList()).thenReturn(tableInfoList);
    when(mockClient.getTablesList()).thenReturn(mockListTablesResponse);

    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    Universe u1 = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    u1 = Universe.saveDetails(u1.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(u1.universeUUID);
    customer.save();

    LOG.info("Created customer " + customer.uuid + " with universe " + u1.universeUUID);
    Result r = tablesController.universeList(customer.uuid, u1.universeUUID);
    JsonNode json = Json.parse(contentAsString(r));
    LOG.info("Fetched table list from universe, response: " + contentAsString(r));
    assertEquals(OK, r.status());
    assertTrue(json.isArray());
    Iterator<JsonNode> it = json.elements();
    int numTables = 0;
    while (it.hasNext()) {
      JsonNode table = it.next();
      String tableName = table.get("tableName").asText();
      String tableType = table.get("tableType").asText();
      String tableKeySpace = table.get("keySpace") != null ? table.get("keySpace").asText() : null;
      // Display table only if table is redis type or table is CQL type but not of system keyspace
      if (tableType.equals("REDIS_TABLE_TYPE") ||
         (!tableKeySpace.toLowerCase().equals("system") && !tableKeySpace.toLowerCase().equals("system_schema"))) {
           numTables++;
      }
      LOG.info("Table name: " + tableName + ", table type: " + tableType);
      assertTrue(tableNames.contains(tableName));
      if (tableName.equals("Table1")) {
        assertEquals(TableType.REDIS_TABLE_TYPE.toString(), tableType);
        assertEquals(tableKeySpace, null);
      } else if (tableName.equals("Table2")) {
        assertEquals(TableType.YQL_TABLE_TYPE.toString(), tableType);
        assertEquals(tableKeySpace, "$$$Default");
      }

    }
    LOG.info("Processed " + numTables + " tables");
    assertEquals(numTables, tableNames.size());
 }

  @Test
  public void testUniverseListMastersNotQueryable() {

    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    Universe u1 = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    customer.addUniverseUUID(u1.universeUUID);
    customer.save();

    Result r = tablesController.universeList(customer.uuid, u1.universeUUID);
    assertEquals(200, r.status());
    assertEquals("Expected error. Masters are not currently queryable.", contentAsString(r));
  }

  @Test
  public void testCreateCassandraTableWithInvalidUUID() {
    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    String authToken = customer.createAuthToken();
    customer.save();

    UUID badUUID = UUID.randomUUID();
    String method = "POST";
    String url = "/api/customers/" + customer.uuid + "/universes/" + badUUID + "/tables";
    ObjectNode emptyJson = Json.newObject();

    Result r = FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, emptyJson);
    assertEquals(BAD_REQUEST, r.status());
    String errMsg = "Cannot find universe " + badUUID;
    assertThat(Json.parse(contentAsString(r)).get("error").asText(), containsString(errMsg));
  }

  @Test
  public void testCreateCassandraTableWithInvalidParams() {
    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    String authToken = customer.createAuthToken();
    Universe universe = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    universe = Universe.saveDetails(universe.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    String method = "POST";
    String url = "/api/customers/" + customer.uuid + "/universes/" + universe.universeUUID +
        "/tables";
    ObjectNode emptyJson = Json.newObject();
    String errorString = "NullPointerException";

    Result result = FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, emptyJson);
    assertEquals(BAD_REQUEST, result.status());
    assertThat(contentAsString(result), containsString(errorString));
  }

  @Test
  public void testCreateCassandraTableWithValidParams() {
    UUID fakeTaskUUID = UUID.randomUUID();
    when(mockCommissioner.submit(Matchers.any(TaskType.class),
        Matchers.any(TableDefinitionTaskParams.class))).thenReturn(fakeTaskUUID);

    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    String authToken = customer.createAuthToken();
    Universe universe = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    universe = Universe.saveDetails(universe.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    String method = "POST";
    String url = "/api/customers/" + customer.uuid + "/universes/" + universe.universeUUID +
        "/tables";
    JsonNode topJson = Json.parse(
        "{" +
          "\"cloud\":\"aws\"," +
          "\"universeUUID\":\"" + universe.universeUUID.toString() + "\"," +
          "\"expectedUniverseVersion\":-1," +
          "\"tableUUID\":null," +
          "\"tableType\":\"YQL_TABLE_TYPE\"," +
          "\"tableDetails\":{" +
            "\"tableName\":\"test_table\"," +
            "\"keyspace\":\"test_ks\"," +
            "\"columns\":[" +
              "{" +
                "\"columnOrder\":1," +
                "\"name\":\"k\"," +
                "\"type\":\"INT\"," +
                "\"isPartitionKey\":true," +
                "\"isClusteringKey\":false" +
              "},{" +
                "\"columnOrder\":2," +
                "\"name\":\"v1\"," +
                "\"type\":\"VARCHAR\"," +
                "\"isPartitionKey\":false," +
                "\"isClusteringKey\":false" +
              "},{" +
                "\"columnOrder\":3," +
                "\"name\":\"v2\"," +
                "\"type\":\"SET\"," +
                "\"keyType\":\"INT\"," +
                "\"isPartitionKey\":false," +
                "\"isClusteringKey\":false" +
              "},{" +
                "\"columnOrder\":4," +
                "\"name\":\"v3\"," +
                "\"type\":\"MAP\"," +
                "\"keyType\":\"UUID\"," +
                "\"valueType\":\"VARCHAR\"," +
                "\"isPartitionKey\":false," +
                "\"isClusteringKey\":false" +
              "}" +
            "]" +
          "}" +
        "}");

    Result result = FakeApiHelper.doRequestWithAuthTokenAndBody(method, url, authToken, topJson);
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(json.get("taskUUID").asText(), fakeTaskUUID.toString());

    CustomerTask task = CustomerTask.find.where().eq("task_uuid", fakeTaskUUID).findUnique();
    assertNotNull(task);
    assertThat(task.getCustomerUUID(), allOf(notNullValue(), equalTo(customer.uuid)));
    assertThat(task.getTargetName(), allOf(notNullValue(), equalTo("test_table")));
    assertThat(task.getType(), allOf(notNullValue(), equalTo(CustomerTask.TaskType.Create)));
    // TODO: Ideally i think the targetUUID for tables should be tableUUID, but currently
    // we don't control the UUID generation for tables from middleware side.
    assertThat(task.getTargetUUID(), allOf(notNullValue(), equalTo(universe.universeUUID)));
  }

  @Test
  public void testDescribeTableSuccess() throws Exception {
    when(mockClient.getTableSchemaByUUID(any(String.class))).thenReturn(mockSchemaResponse);

    // Creating a fake table
    UUID tableUUID = UUID.randomUUID();
    List<ColumnSchema> columnSchemas = new LinkedList<>();
    columnSchemas.add(new ColumnSchema.ColumnSchemaBuilder("mock_column", Type.INT32)
        .id(1)
        .hashKey(true)
        .build());
    Schema schema = new Schema(columnSchemas);
    when(mockSchemaResponse.getSchema()).thenReturn(schema);
    when(mockSchemaResponse.getTableName()).thenReturn("mock_table");
    when(mockSchemaResponse.getNamespace()).thenReturn("mock_ks");
    when(mockSchemaResponse.getTableType()).thenReturn(TableType.YQL_TABLE_TYPE);
    when(mockSchemaResponse.getTableId()).thenReturn(tableUUID.toString().replace("-", ""));

    // Creating fake authentication
    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    Universe universe = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    universe = Universe.saveDetails(universe.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    Result result = tablesController.describe(customer.uuid, universe.universeUUID, tableUUID);
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(tableUUID.toString(), json.get("tableUUID").asText());
    assertEquals("YQL_TABLE_TYPE", json.get("tableType").asText());
    assertEquals("mock_table", json.at("/tableDetails/tableName").asText());
    assertEquals("mock_ks", json.at("/tableDetails/keyspace").asText());
    assertEquals("mock_column", json.at("/tableDetails/columns/0/name").asText());
  }

  @Test
  public void testDescribeTableFailure() throws Exception {
    // Creating a fake table
    String mockTableUUID1 = UUID.randomUUID().toString().replace("-", "");
    UUID mockTableUUID2 = UUID.randomUUID();
    when(mockSchemaResponse.getTableId()).thenReturn(mockTableUUID1);
    when(mockClient.getTablesList()).thenReturn(mockListTablesResponse);
    when(mockClient.getTableSchemaByUUID(any(String.class))).thenReturn(mockSchemaResponse);

    // Creating fake authentication
    Customer customer = Customer.create("Valid Customer", "abd@def.ghi", "password");
    Universe universe = Universe.create("Universe-1", UUID.randomUUID(), customer.getCustomerId());
    universe = Universe.saveDetails(universe.universeUUID, ApiUtils.mockUniverseUpdater());
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    Result result = tablesController.describe(customer.uuid, universe.universeUUID, mockTableUUID2);
    assertEquals(BAD_REQUEST, result.status());
    //String errMsg = "Invalid Universe UUID: " + universe.universeUUID;
    String errMsg = "UUID of table in schema (" + mockTableUUID2.toString().replace("-", "") +
        ") did not match UUID of table in request (" + mockTableUUID1 + ").";
    assertEquals(errMsg, Json.parse(contentAsString(result)).get("error").asText());
  }

  @Test
  public void testGetColumnTypes() {
    Result result = FakeApiHelper.doRequest("GET", "/api/metadata/column_types");
    Set<ColumnDetails.YQLDataType> types = ImmutableSet.copyOf(ColumnDetails.YQLDataType.values());
    assertEquals(OK, result.status());
    JsonNode resultContent = Json.parse(contentAsString(result));
    assertThat(resultContent, notNullValue());
    JsonNode primitives = resultContent.get("primitives");
    JsonNode collections = resultContent.get("collections");
    Set<ColumnDetails.YQLDataType> resultTypes = new HashSet<>();

    // Check primitives
    for (int i = 0; i < primitives.size(); ++i) {
      String primitive = primitives.get(i).asText();
      ColumnDetails.YQLDataType type = ColumnDetails.YQLDataType.valueOf(primitive);
      assertFalse(type.isCollection());
      resultTypes.add(type);
    }

    // Check collections
    for (int i = 0; i < collections.size(); ++i) {
      String collection = collections.get(i).asText();
      ColumnDetails.YQLDataType type = ColumnDetails.YQLDataType.valueOf(collection);
      assertTrue(type.isCollection());
      resultTypes.add(type);
    }

    // Check all
    assertTrue(resultTypes.containsAll(types));
  }
}
