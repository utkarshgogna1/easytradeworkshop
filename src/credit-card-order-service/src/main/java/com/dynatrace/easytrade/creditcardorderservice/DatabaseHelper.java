package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class DatabaseHelper {
    private final String INSERT_ORDER_QUERY = "INSERT INTO [dbo].[CreditCardOrders] ([Id], [AccountId], [Email], [Name], [ShippingAddress], [CardLevel]) VALUES (?, ?, ?, ?, ?, ?)";
    private final String INSERT_STATUS_QUERY = "INSERT INTO [dbo].[CreditCardOrderStatus] ([CreditCardOrderId], [Timestamp], [Status], [Details]) VALUES (?, ?, ?, ?)";
    private final String INSERT_CREDIT_CARD_QUERY = "INSERT INTO [dbo].[CreditCards] ([CreditCardOrderId], [Level], [Number], [Cvs], [ValidDate]) VALUES (?, ?, ?, ?, ?)";
    private final String UPDATE_ORDER_QUERY = "UPDATE CreditCardOrders SET ShippingId = ? WHERE Id = ?";
    private final String COUNT_ORDER_BY_ACCOUNT_ID_QUERY = "SELECT COUNT(*) FROM CreditCardOrders WHERE AccountId = ?";
    private final String SHIPPING_ADDRESS_DATA_QUERY = "SELECT Name, Email, ShippingAddress FROM CreditCardOrders WHERE Id = ?";
    private final String CC_MANUFACTURE_DETAILS_QUERY = "SELECT Id, Name, CardLevel FROM CreditCardOrders WHERE Id = ?";
    private final String GET_ORDER_BY_ACCOUNT_ID = "SELECT Id FROM CreditCardOrders WHERE AccountId = ?";
    private final String GET_LAST_STATUS_QUERY = "SELECT TOP 1 * FROM CreditCardOrderStatus WHERE CreditCardOrderId = ? ORDER BY Timestamp DESC";
    private final String GET_STATUS_LIST_QUERY = "SELECT * FROM CreditCardOrderStatus WHERE CreditCardOrderId = ? ORDER BY Timestamp DESC";
    private final String GET_LAST_STATUS_BY_ACCOUNT_ID_QUERY = "SELECT TOP 1 * FROM CreditCardOrderStatus ccos WHERE ccos.CreditCardOrderId = (SELECT cco.Id FROM CreditCardOrders cco WHERE cco.AccountId = ?) ORDER BY Timestamp DESC";
    private final String DELETE_ORDER_STATUS_BY_ACCOUNT_ID_QUERY = "DELETE FROM CreditCardOrderStatus WHERE CreditCardOrderId = (SELECT y.Id FROM CreditCardOrders y WHERE y.AccountId = ?)";
    private final String DELETE_CREDIT_CARD_BY_ACCOUNT_ID_QUERY = "DELETE FROM CreditCards WHERE CreditCardOrderId = (SELECT y.Id FROM CreditCardOrders y WHERE y.AccountId = ?)";
    private final String DELETE_ORDER_BY_ACCOUNT_ID_QUERY = "DELETE FROM CreditCardOrders WHERE AccountId = ?";
    private final String GET_ORDER_ID_AND_CURRENT_STATUS = "select x.CreditCardOrderId, x.Status from CreditCardOrderStatus x inner join (select max(Id) Id, CreditCardOrderId from CreditCardOrderStatus group by CreditCardOrderId) y on x.CreditCardOrderId = y.CreditCardOrderId and x.Id = y.Id";
    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);

    public String insertNewOrder(Connection conn, CreditCardOrderRequest request) throws SQLException {
        PreparedStatement query = conn.prepareStatement(INSERT_ORDER_QUERY);
        String guid = UUID.randomUUID().toString();
        query.setString(1, guid);
        query.setInt(2, request.accountId());
        query.setString(3, request.email());
        query.setString(4, request.name());
        query.setString(5, request.shippingAddress());
        query.setString(6, request.cardLevel());
        query.executeUpdate();
        query.close();
        return guid;
    }

    public Optional<ShippingAddressResponse> getShippingAddress(Connection conn, String creditCardOrderId)
            throws SQLException {
        PreparedStatement query = conn.prepareStatement(SHIPPING_ADDRESS_DATA_QUERY);
        query.setString(1, creditCardOrderId);
        ResultSet rs = query.executeQuery();
        if (!rs.next()) {
            query.close();
            return Optional.empty();
        }
        var result = Optional.of(new ShippingAddressResponse(
                rs.getString("ShippingAddress"),
                rs.getString("Name"),
                rs.getString("Email")));
        query.close();
        return result;
    }

    public ManufactureRequest getCreditCardManufactureData(Connection conn, String creditCardOrderId)
            throws SQLException {
        PreparedStatement query = conn.prepareStatement(CC_MANUFACTURE_DETAILS_QUERY);
        query.setString(1, creditCardOrderId);
        ResultSet rs = query.executeQuery();
        if (!rs.next()) {
            query.close();
            return null;
        }
        var result = new ManufactureRequest(
                rs.getString("Id"),
                rs.getString("Name"),
                rs.getString("CardLevel"));
        query.close();
        return result;
    }

    public Integer getOrderCountForAccountId(Connection conn, Integer accountId) throws SQLException {
        PreparedStatement query = conn.prepareStatement(COUNT_ORDER_BY_ACCOUNT_ID_QUERY);
        query.setInt(1, accountId);
        ResultSet rs = query.executeQuery();
        rs.next();
        var result = rs.getInt(1);
        query.close();
        return result;
    }

    public Optional<String> getOrderIdForAccount(Connection conn, Integer accountId) throws SQLException {
        PreparedStatement query = conn.prepareStatement(GET_ORDER_BY_ACCOUNT_ID);
        query.setInt(1, accountId);
        ResultSet rs = query.executeQuery();

        Optional<String> result;
        if (rs.next()) {
            result = Optional.of(rs.getString("Id"));
        } else {
            result = Optional.empty();
        }
        query.close();
        return result;
    }

    public List<CreditCardOrderStatus> getOrderStatusList(Connection conn, String creditCardOrderId)
            throws SQLException {
        PreparedStatement query = conn.prepareStatement(GET_STATUS_LIST_QUERY);
        query.setString(1, creditCardOrderId);
        ResultSet rs = query.executeQuery();

        List<CreditCardOrderStatus> result = new ArrayList<>();
        while (rs.next()) {
            result.add(CreditCardOrderStatus.fromResultSet(rs));
        }
        query.close();
        return result;
    }

    public CreditCardOrderStatus getLastOrderStatus(Connection conn, String creditCardOrderId) throws SQLException {
        PreparedStatement query = conn.prepareStatement(GET_LAST_STATUS_QUERY);
        query.setString(1, creditCardOrderId);
        ResultSet rs = query.executeQuery();

        CreditCardOrderStatus status = null;
        if (rs.next()) {
            status = CreditCardOrderStatus.fromResultSet(rs);
        }
        query.close();
        return status;
    }

    public List<String> getOrderIdsWithStatusOrderCreated(Connection conn) throws SQLException {
        PreparedStatement query = conn.prepareStatement(GET_ORDER_ID_AND_CURRENT_STATUS);
        ResultSet rs = query.executeQuery();

        ArrayList<String> orders = new ArrayList<>();
        while (rs.next()) {
            String id = rs.getString(1);
            String status = rs.getString(2);
            if (StatusType.valueOf(status.toUpperCase()) == StatusType.ORDER_CREATED) {
                orders.add(id);
            }
        }
        query.close();
        return orders;
    }

    public void insertNewStatus(String orderId, StatusType statusType) throws SQLException {
        try (Connection conn = getConnection()) {
            insertNewStatus(conn, orderId, statusType, statusType.getDescription());
        }
    }

    public void insertNewStatus(String orderId, StatusType statusType, String details) throws SQLException {
        try (Connection conn = getConnection()) {
            insertNewStatus(conn, orderId, statusType, details);
        }
    }

    public void insertNewStatus(Connection conn, String orderId, StatusType statusType) throws SQLException {
        insertNewStatus(conn, orderId, statusType, statusType.getDescription());
    }

    public void insertNewStatus(Connection conn, String orderId, StatusType statusType, String details)
            throws SQLException {
        Timestamp timestamp = Timestamp.valueOf(OffsetDateTime.now().toLocalDateTime());
        logger.debug("Inserting new status with [orderID::" + orderId + "] [date::" + timestamp + "] [status::"
                + statusType.getType() + "] [details::" + details + "]");
        PreparedStatement query = conn.prepareStatement(INSERT_STATUS_QUERY);
        query.setString(1, orderId);
        query.setTimestamp(2, timestamp);
        query.setString(3, statusType.getType());
        query.setString(4, details);
        query.executeUpdate();
        query.close();
    }

    public void insertNewCreditCard(String orderId, CreditCardRequest request) throws SQLException {
        try (Connection conn = getConnection()) {
            PreparedStatement query = conn.prepareStatement(INSERT_CREDIT_CARD_QUERY);
            query.setString(1, orderId);
            query.setString(2, request.cardLevel());
            query.setString(3, request.cardNumber());
            query.setString(4, request.cardCVS());
            query.setTimestamp(5, Timestamp.valueOf(request.cardValidDate().toLocalDateTime()));
            query.executeUpdate();
            query.close();
        }
    }

    public void updateOrder(String orderId, ShippingIdRequest shippingIdRequest) throws SQLException {
        try (Connection conn = getConnection()) {
            PreparedStatement query = conn.prepareStatement(UPDATE_ORDER_QUERY);
            query.setString(1, shippingIdRequest.shippingId());
            query.setString(2, orderId);
            query.executeUpdate();
            query.close();
        }
    }

    /**
     * Lazily-initialized, shared connection pool.
     *
     * <p>Previously every DB operation opened a brand-new {@code DriverManager.getConnection()}
     * and closed it — under load this causes connection-establishment churn and (against a
     * shared database instance) connection-pool exhaustion that cascades across services.
     * A bounded HikariCP pool reuses a small set of connections instead, which is the primary
     * code-level mitigation for the shared-DB bottleneck identified in the 100x scaling review.
     *
     * <p>The connection string is unchanged ({@code MSSQL_CONNECTIONSTRING}). Pool size and
     * timeouts are tunable via environment variables so they can be scaled without a code change.
     */
    private static volatile DataSource dataSource;

    private static DataSource getDataSource() {
        DataSource local = dataSource;
        if (local == null) {
            synchronized (DatabaseHelper.class) {
                local = dataSource;
                if (local == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(System.getenv("MSSQL_CONNECTIONSTRING"));
                    config.setPoolName("credit-card-order-pool");
                    config.setMaximumPoolSize(envInt("DB_POOL_MAX_SIZE", 10));
                    config.setMinimumIdle(envInt("DB_POOL_MIN_IDLE", 2));
                    config.setConnectionTimeout(envLong("DB_POOL_CONNECTION_TIMEOUT_MS", 30_000L));
                    config.setIdleTimeout(envLong("DB_POOL_IDLE_TIMEOUT_MS", 600_000L));
                    config.setMaxLifetime(envLong("DB_POOL_MAX_LIFETIME_MS", 1_800_000L));
                    local = new HikariDataSource(config);
                    dataSource = local;
                    logger.info("Initialized DB connection pool [maxPoolSize={}]", config.getMaximumPoolSize());
                }
            }
        }
        return local;
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer for {}='{}', using default {}", name, value, defaultValue);
            return defaultValue;
        }
    }

    private static long envLong(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid long for {}='{}', using default {}", name, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns a pooled connection. Callers must {@code close()} it (all callers already use
     * try-with-resources); closing returns the connection to the pool rather than tearing
     * down a physical connection.
     */
    public Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    public Optional<CreditCardOrderStatus> getLastOrderStatusForAccountId(Connection conn, Integer accountId)
            throws SQLException {
        PreparedStatement query = conn.prepareStatement(GET_LAST_STATUS_BY_ACCOUNT_ID_QUERY);
        query.setInt(1, accountId);
        ResultSet rs = query.executeQuery();

        Optional<CreditCardOrderStatus> result;
        if (rs.next()) {
            result = Optional.of(CreditCardOrderStatus.fromResultSet(rs));
        } else {
            result = Optional.empty();
        }
        query.close();
        return result;
    }

    public void deleteOrderForAccountId(Connection conn, Integer accountId) throws SQLException {
        deleteByAccountId(conn, accountId, DELETE_ORDER_STATUS_BY_ACCOUNT_ID_QUERY);
        deleteByAccountId(conn, accountId, DELETE_CREDIT_CARD_BY_ACCOUNT_ID_QUERY);
        deleteByAccountId(conn, accountId, DELETE_ORDER_BY_ACCOUNT_ID_QUERY);
    }

    private void deleteByAccountId(Connection conn, Integer accountId, String deleteQuery) throws SQLException {
        PreparedStatement query = conn.prepareStatement(deleteQuery);
        query.setInt(1, accountId);
        query.executeUpdate();
        query.close();
    }
}
