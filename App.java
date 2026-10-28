import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection; // ★ SQLite接続用に変更
import java.sql.DriverManager; // ★ SQLite接続用に変更
import java.sql.PreparedStatement; // ★ SQLを安全に実行するため追加
import java.sql.ResultSet; // ★ SELECT結果の読み出し用に追加
import java.sql.SQLException; // ★ DBエラー処理用に追加
import java.sql.Statement; // ★ テーブル作成用に追加
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.text.Collator;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList; // ★ SELECT結果の保持用に追加
import java.util.HashMap;
import java.util.HashSet;
import java.util.List; // ★ SELECT結果の保持用に追加
import java.util.Locale;
import java.util.Set;

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db"; // ★ CSVではなくSQLiteへ接続
    private static final Path LEGACY_CSV = Path.of("todos.csv");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, User> SESSIONS = new ConcurrentHashMap<>();

    private record Todo(int id, String title, boolean done, String deadline, String category) {
    } // ★ DBの1行を表す

    private record User(int id, String username) {
    }
    private record Group(int id, String name, String description, int leaderUserId, String role) {
    }
    private record GroupMember(int userId, String username, String role) {
    }
    private record MembershipRequest(int id, int groupId, String groupName, String type, String status,
            String requesterName, String targetName) {
    }
    private record ActionResult(boolean success, String message) {
    }

    private static Connection connect() throws SQLException { // ★ DB接続処理へ変更
        Connection connection = DriverManager.getConnection(DB_URL); // ★ todos.dbへ接続
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void initializeDatabase() throws SQLException, IOException, GeneralSecurityException { // ★ DB初期化を追加
        String sql = "CREATE TABLE IF NOT EXISTS todos (" // ★ todos表を定義
                + "id INTEGER PRIMARY KEY, " // ★ id列を定義
                + "title TEXT, " // ★ title列を定義
                + "done INTEGER, " // ★ done列を定義
                + "deadline TEXT, " // ★ 締め切り日列を定義
                + "category TEXT DEFAULT '未分類')"; // カテゴリ列を定義
        try (Connection connection = connect(); // ★ DB接続を自動で閉じる
                Statement statement = connection.createStatement()) { // ★ DDL実行用Statement
            boolean isNewDatabase;
            try (ResultSet tables = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'todos'")) {
                isNewDatabase = !tables.next();
            }
            statement.execute(sql); // ★ todos表がなければ作成
            statement.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS groups ("
                    + "id INTEGER PRIMARY KEY, name TEXT NOT NULL CHECK(length(trim(name)) > 0), "
                    + "description TEXT NOT NULL DEFAULT '', leader_user_id INTEGER NOT NULL, "
                    + "FOREIGN KEY (leader_user_id) REFERENCES users(id) ON DELETE RESTRICT)");
            statement.execute("CREATE TABLE IF NOT EXISTS group_members ("
                    + "group_id INTEGER NOT NULL, user_id INTEGER NOT NULL, "
                    + "role TEXT NOT NULL CHECK(role IN ('LEADER', 'MEMBER')), "
                    + "PRIMARY KEY (group_id, user_id), "
                    + "FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_leader_per_group "
                    + "ON group_members(group_id) WHERE role = 'LEADER'");
            statement.execute("CREATE TABLE IF NOT EXISTS group_membership_requests ("
                    + "id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, "
                    + "type TEXT NOT NULL CHECK(type IN ('MEMBER_INVITE', 'JOIN_REQUEST')), "
                    + "requester_user_id INTEGER NOT NULL, target_user_id INTEGER NOT NULL, "
                    + "status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'APPROVED', 'REJECTED')), "
                    + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, decided_at TEXT, "
                    + "FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (requester_user_id) REFERENCES users(id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_membership_request "
                    + "ON group_membership_requests(group_id, target_user_id) WHERE status = 'PENDING'");
            statement.execute("CREATE TABLE IF NOT EXISTS group_todos ("
                    + "id INTEGER PRIMARY KEY, group_id INTEGER NOT NULL, title TEXT NOT NULL, "
                    + "done INTEGER NOT NULL DEFAULT 0 CHECK(done IN (0, 1)), deadline TEXT, "
                    + "category TEXT NOT NULL DEFAULT '未分類', "
                    + "FOREIGN KEY (group_id) REFERENCES groups(id) ON DELETE CASCADE)");
            statement.execute("CREATE INDEX IF NOT EXISTS group_todos_by_group ON group_todos(group_id, id)");
            statement.execute("CREATE TABLE IF NOT EXISTS app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            boolean hasTheme = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(users)")) {
                while (columns.next()) {
                    if ("theme".equals(columns.getString("name"))) {
                        hasTheme = true;
                    }
                }
            }
            if (!hasTheme) {
                statement.execute("ALTER TABLE users ADD COLUMN theme TEXT NOT NULL DEFAULT 'dark'");
            }
            boolean hasDeadline = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) {
                    if ("deadline".equals(columns.getString("name"))) {
                        hasDeadline = true;
                    }
                }
            }
            if (!hasDeadline) {
                statement.execute("ALTER TABLE todos ADD COLUMN deadline TEXT");
            }
            boolean hasCategory = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) {
                    if ("category".equals(columns.getString("name"))) {
                        hasCategory = true;
                    }
                }
            }
            if (!hasCategory) {
                statement.execute("ALTER TABLE todos ADD COLUMN category TEXT DEFAULT '未分類'");
            }
            boolean hasUserId = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(todos)")) {
                while (columns.next()) {
                    if ("user_id".equals(columns.getString("name"))) {
                        hasUserId = true;
                    }
                }
            }
            if (!hasUserId) {
                statement.execute("ALTER TABLE todos ADD COLUMN user_id INTEGER");
            }
            if (isNewDatabase) {
                importLegacyCsv(connection);
            }
            int defaultUserId;
            try (PreparedStatement findUser = connection.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                findUser.setString(1, "わたし");
                try (ResultSet result = findUser.executeQuery()) {
                    if (result.next()) {
                        defaultUserId = result.getInt("id");
                    } else {
                        try (PreparedStatement createUser = connection.prepareStatement(
                                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)")) {
                            defaultUserId = generateAvailableUserId(connection);
                            createUser.setInt(1, defaultUserId);
                            createUser.setString(2, "わたし");
                            createUser.setString(3, hashPassword("0000"));
                            createUser.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement migrate = connection.prepareStatement("UPDATE todos SET user_id = ? WHERE user_id IS NULL")) {
                migrate.setInt(1, defaultUserId);
                migrate.executeUpdate();
            }
        }
        migrateExistingIdsToEightDigits();
    }

    private static int generateAvailableUserId(Connection connection) throws SQLException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int candidate = 10_000_000 + RANDOM.nextInt(90_000_000);
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                statement.setInt(1, candidate);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return candidate;
                }
            }
        }
        throw new SQLException("一意なユーザーIDを生成できませんでした");
    }

    private static void migrateExistingIdsToEightDigits() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
                Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM app_meta WHERE key = 'random_eight_digit_ids_v1'");
                        ResultSet result = check.executeQuery()) {
                    if (result.next()) {
                        connection.rollback();
                        return;
                    }
                }

                Map<Integer, Integer> userIds = createIdMap(connection, "users");
                Map<Integer, Integer> groupIds = createIdMap(connection, "groups");
                updateReferences(connection, userIds, "todos", "user_id");
                updateReferences(connection, userIds, "groups", "leader_user_id");
                updateReferences(connection, userIds, "group_members", "user_id");
                updateReferences(connection, userIds, "group_membership_requests", "requester_user_id");
                updateReferences(connection, userIds, "group_membership_requests", "target_user_id");
                updatePrimaryIds(connection, userIds, "users");

                updateReferences(connection, groupIds, "group_members", "group_id");
                updateReferences(connection, groupIds, "group_membership_requests", "group_id");
                updateReferences(connection, groupIds, "group_todos", "group_id");
                updatePrimaryIds(connection, groupIds, "groups");

                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO app_meta (key, value) VALUES ('random_eight_digit_ids_v1', 'done')")) {
                    mark.executeUpdate();
                }
                try (ResultSet violation = pragma.executeQuery("PRAGMA foreign_key_check")) {
                    if (violation.next()) throw new SQLException("ID移行後の参照整合性を確認できませんでした");
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                pragma.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private static Map<Integer, Integer> createIdMap(Connection connection, String table) throws SQLException {
        Map<Integer, Integer> ids = new HashMap<>();
        Set<Integer> generated = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id FROM " + table)) {
            while (result.next()) {
                int candidate;
                do {
                    candidate = 10_000_000 + RANDOM.nextInt(90_000_000);
                } while (!generated.add(candidate));
                ids.put(result.getInt("id"), candidate);
            }
        }
        return ids;
    }

    private static void updateReferences(Connection connection, Map<Integer, Integer> ids,
            String table, String column) throws SQLException {
        try (PreparedStatement makeTemporary = connection.prepareStatement(
                "UPDATE " + table + " SET " + column + " = -" + column + " WHERE " + column + " = ?");
                PreparedStatement assignFinal = connection.prepareStatement(
                        "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?")) {
            for (Integer oldId : ids.keySet()) {
                makeTemporary.setInt(1, oldId);
                makeTemporary.addBatch();
            }
            makeTemporary.executeBatch();
            for (Map.Entry<Integer, Integer> entry : ids.entrySet()) {
                assignFinal.setInt(1, entry.getValue());
                assignFinal.setInt(2, -entry.getKey());
                assignFinal.addBatch();
            }
            assignFinal.executeBatch();
        }
    }

    private static void updatePrimaryIds(Connection connection, Map<Integer, Integer> ids, String table)
            throws SQLException {
        try (PreparedStatement makeTemporary = connection.prepareStatement(
                "UPDATE " + table + " SET id = -id WHERE id = ?");
                PreparedStatement assignFinal = connection.prepareStatement(
                        "UPDATE " + table + " SET id = ? WHERE id = ?")) {
            for (Integer oldId : ids.keySet()) {
                makeTemporary.setInt(1, oldId);
                makeTemporary.addBatch();
            }
            makeTemporary.executeBatch();
            for (Map.Entry<Integer, Integer> entry : ids.entrySet()) {
                assignFinal.setInt(1, entry.getValue());
                assignFinal.setInt(2, -entry.getKey());
                assignFinal.addBatch();
            }
            assignFinal.executeBatch();
        }
    }

    private static void importLegacyCsv(Connection connection) throws IOException, SQLException {
        if (!Files.exists(LEGACY_CSV)) {
            return;
        }
        String sql = "INSERT INTO todos (id, title, done, deadline, category) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String line : Files.readAllLines(LEGACY_CSV, StandardCharsets.UTF_8)) {
                String[] values = line.split(",", 4);
                if (values.length < 3) {
                    continue;
                }
                try {
                    statement.setInt(1, Integer.parseInt(values[0]));
                    statement.setString(2, values[2]);
                    statement.setInt(3, Integer.parseInt(values[1]));
                    statement.setString(4, values.length == 4 ? values[3] : "");
                    statement.setString(5, "未分類");
                    statement.executeUpdate();
                } catch (NumberFormatException ignored) {
                    // 壊れた行だけを読み飛ばし、他のTodoは移行する
                }
            }
        }
    }

    private static void addTodo(int userId, String title, String deadline, String category) throws SQLException { // ★ CSV保存をINSERTへ変更
        String sql = "INSERT INTO todos (user_id, title, done, deadline, category) VALUES (?, ?, ?, ?, ?)"; // ★ 追加SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, userId);
            statement.setString(2, title); // ★ タイトルを設定
            statement.setInt(3, 0); // ★ 未完了を0で保存
            statement.setString(4, deadline); // ★ 締め切り日を設定
            statement.setString(5, category); // カテゴリを設定
            statement.executeUpdate(); // ★ INSERTを実行
        }
    }

    private static void completeTodo(int userId, int id) throws SQLException { // ★ リスト更新をUPDATEへ変更
        String sql = "UPDATE todos SET done = CASE WHEN done = 0 THEN 1 ELSE 0 END WHERE id = ? AND user_id = ?"; // 完了状態を切り替えるSQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, id); // ★ 対象idを設定
            statement.setInt(2, userId);
            statement.executeUpdate(); // ★ UPDATEを実行
        }
    }

    private static void editTodo(int userId, int id, String title) throws SQLException {
        String sql = "UPDATE todos SET title = ? WHERE id = ? AND user_id = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setInt(2, id);
            statement.setInt(3, userId);
            statement.executeUpdate();
        }
    }

    private static void deleteTodo(int userId, int id) throws SQLException { // ★ リスト削除をDELETEへ変更
        String sql = "DELETE FROM todos WHERE id = ? AND user_id = ?"; // ★ 削除SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, id); // ★ 対象idを設定
            statement.setInt(2, userId);
            statement.executeUpdate(); // ★ DELETEを実行
        }
    }

    private static void deleteCompletedTodos(int userId) throws SQLException {
        String sql = "DELETE FROM todos WHERE done = 1 AND user_id = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
    }

    private static List<Todo> findAllTodos(int userId) throws SQLException { // ★ CSV読込をSELECTへ変更
        List<Todo> todos = new ArrayList<>(); // ★ SELECT結果の格納先
        String sql = "SELECT id, title, done, deadline, category FROM todos WHERE user_id = ? ORDER BY id"; // ★ 一覧取得SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) { // ★ SELECTを実行
            while (resultSet.next()) { // ★ 結果を1行ずつ読み出す
                todos.add(new Todo( // ★ DBの行からTodoを作成
                        resultSet.getInt("id"), // ★ idを読み出す
                        resultSet.getString("title"), // ★ titleを読み出す
                        resultSet.getInt("done") != 0, // ★ doneをbooleanへ変換
                        resultSet.getString("deadline"), // ★ 締め切り日を読み出す
                        resultSet.getString("category"))); // カテゴリを読み出す
            }
            }
        }
        return todos; // ★ DBから取得した一覧を返す
    }

    private static List<Todo> findGroupTodos(int groupId, int userId) throws SQLException {
        List<Todo> todos = new ArrayList<>();
        String sql = "SELECT gt.id, gt.title, gt.done, gt.deadline, gt.category "
                + "FROM group_todos gt JOIN group_members gm ON gm.group_id = gt.group_id "
                + "WHERE gt.group_id = ? AND gm.user_id = ? ORDER BY gt.id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    todos.add(new Todo(result.getInt("id"), result.getString("title"),
                            result.getInt("done") != 0, result.getString("deadline"),
                            result.getString("category")));
                }
            }
        }
        return todos;
    }

    private static ActionResult addGroupTodo(int groupId, int userId, String title, String deadline,
            String category) throws SQLException {
        String sql = "INSERT INTO group_todos (group_id, title, done, deadline, category) "
                + "SELECT ?, ?, 0, ?, ? WHERE EXISTS (SELECT 1 FROM group_members "
                + "WHERE group_id = ? AND user_id = ? AND role = 'LEADER')";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId); statement.setString(2, title); statement.setString(3, deadline);
            statement.setString(4, category); statement.setInt(5, groupId); statement.setInt(6, userId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループTodoを追加しました")
                    : new ActionResult(false, "グループTodoを追加する権限がありません");
        }
    }

    private static ActionResult editGroupTodo(int groupId, int todoId, int userId, String title,
            String deadline, String category) throws SQLException {
        String sql = "UPDATE group_todos SET title = ?, deadline = ?, category = ? "
                + "WHERE id = ? AND group_id = ? AND EXISTS (SELECT 1 FROM group_members "
                + "WHERE group_id = ? AND user_id = ? AND role = 'LEADER')";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title); statement.setString(2, deadline); statement.setString(3, category);
            statement.setInt(4, todoId); statement.setInt(5, groupId); statement.setInt(6, groupId);
            statement.setInt(7, userId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループTodoを編集しました")
                    : new ActionResult(false, "グループTodoを編集する権限がないか、Todoが存在しません");
        }
    }

    private static ActionResult toggleGroupTodo(int groupId, int todoId, int userId) throws SQLException {
        String sql = "UPDATE group_todos SET done = CASE done WHEN 0 THEN 1 ELSE 0 END "
                + "WHERE id = ? AND group_id = ? AND EXISTS (SELECT 1 FROM group_members "
                + "WHERE group_id = ? AND user_id = ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, todoId); statement.setInt(2, groupId); statement.setInt(3, groupId);
            statement.setInt(4, userId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "完了状態を変更しました")
                    : new ActionResult(false, "このグループTodoを変更する権限がありません");
        }
    }

    private static ActionResult deleteGroupTodo(int groupId, int todoId, int userId) throws SQLException {
        String sql = "DELETE FROM group_todos WHERE id = ? AND group_id = ? AND EXISTS "
                + "(SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ? AND role = 'LEADER')";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, todoId); statement.setInt(2, groupId); statement.setInt(3, groupId);
            statement.setInt(4, userId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループTodoを削除しました")
                    : new ActionResult(false, "グループTodoを削除する権限がないか、Todoが存在しません");
        }
    }

    private static void createGroup(int userId, String name, String description) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertGroup = connection.prepareStatement(
                    "INSERT OR IGNORE INTO groups (id, name, description, leader_user_id) VALUES (?, ?, ?, ?)");
                    PreparedStatement insertMember = connection.prepareStatement(
                            "INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, 'LEADER')")) {
                int groupId = 0;
                for (int attempt = 0; attempt < 20; attempt++) {
                    int candidate = 10_000_000 + RANDOM.nextInt(90_000_000);
                    insertGroup.setInt(1, candidate);
                    insertGroup.setString(2, name);
                    insertGroup.setString(3, description);
                    insertGroup.setInt(4, userId);
                    if (insertGroup.executeUpdate() == 1) {
                        groupId = candidate;
                        break;
                    }
                }
                if (groupId == 0) {
                    throw new SQLException("一意なグループIDを生成できませんでした");
                }
                insertMember.setInt(1, groupId);
                insertMember.setInt(2, userId);
                insertMember.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static List<Group> findGroupsForUser(int userId) throws SQLException {
        List<Group> groups = new ArrayList<>();
        String sql = "SELECT g.id, g.name, g.description, g.leader_user_id, gm.role "
                + "FROM groups g JOIN group_members gm ON gm.group_id = g.id "
                + "WHERE gm.user_id = ? ORDER BY g.id DESC";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    groups.add(new Group(result.getInt("id"), result.getString("name"),
                            result.getString("description"), result.getInt("leader_user_id"),
                            result.getString("role")));
                }
            }
        }
        return groups;
    }

    private static Group findGroupForMember(int groupId, int userId) throws SQLException {
        String sql = "SELECT g.id, g.name, g.description, g.leader_user_id, gm.role "
                + "FROM groups g JOIN group_members gm ON gm.group_id = g.id "
                + "WHERE g.id = ? AND gm.user_id = ?";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Group(result.getInt("id"), result.getString("name"),
                        result.getString("description"), result.getInt("leader_user_id"),
                        result.getString("role")) : null;
            }
        }
    }

    private static String findGroupRole(Connection connection, int groupId, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?")) {
            statement.setInt(1, groupId);
            statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("role") : null;
            }
        }
    }

    private static boolean isGroupLeader(Connection connection, int groupId, int userId) throws SQLException {
        return "LEADER".equals(findGroupRole(connection, groupId, userId));
    }

    private static boolean leadsAnyGroup(int userId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM groups WHERE leader_user_id = ? LIMIT 1")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<GroupMember> findGroupMembers(int groupId, int memberUserId) throws SQLException {
        List<GroupMember> members = new ArrayList<>();
        String sql = "SELECT member.user_id, u.username, member.role "
                + "FROM group_members member JOIN users u ON u.id = member.user_id "
                + "WHERE member.group_id = ? AND EXISTS (SELECT 1 FROM group_members viewer "
                + "WHERE viewer.group_id = member.group_id AND viewer.user_id = ?) "
                + "ORDER BY CASE member.role WHEN 'LEADER' THEN 0 ELSE 1 END, u.username";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, memberUserId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(new GroupMember(result.getInt("user_id"), result.getString("username"),
                            result.getString("role")));
                }
            }
        }
        return members;
    }

    private static ActionResult updateGroup(int groupId, int leaderUserId, String name, String description)
            throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE groups SET name = ?, description = ? WHERE id = ? AND leader_user_id = ?")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setInt(3, groupId);
            statement.setInt(4, leaderUserId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループ情報を変更しました")
                    : new ActionResult(false, "グループ情報を変更する権限がありません");
        }
    }

    private static ActionResult removeGroupMember(int groupId, int leaderUserId, int targetUserId)
            throws SQLException {
        if (leaderUserId == targetUserId) {
            return new ActionResult(false, "リーダー自身はメンバーから削除できません");
        }
        String sql = "DELETE FROM group_members WHERE group_id = ? AND user_id = ? AND role = 'MEMBER' "
                + "AND EXISTS (SELECT 1 FROM groups WHERE id = ? AND leader_user_id = ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, targetUserId);
            statement.setInt(3, groupId);
            statement.setInt(4, leaderUserId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "メンバーを削除しました")
                    : new ActionResult(false, "対象メンバーがいないか、削除する権限がありません");
        }
    }

    private static ActionResult cancelGroupInvite(int groupId, int requestId, int leaderUserId)
            throws SQLException {
        String sql = "UPDATE group_membership_requests SET status = 'REJECTED', decided_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND group_id = ? AND type = 'MEMBER_INVITE' AND status = 'PENDING' "
                + "AND EXISTS (SELECT 1 FROM groups WHERE id = ? AND leader_user_id = ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, requestId);
            statement.setInt(2, groupId);
            statement.setInt(3, groupId);
            statement.setInt(4, leaderUserId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "招待を取り消しました")
                    : new ActionResult(false, "取り消せる承認待ちの招待がないか、権限がありません");
        }
    }

    private static ActionResult transferGroupLeadership(int groupId, int leaderUserId, int targetUserId)
            throws SQLException {
        if (leaderUserId == targetUserId) {
            return new ActionResult(false, "現在のリーダー以外の一般メンバーを選択してください");
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (!isGroupLeader(connection, groupId, leaderUserId)) {
                    return rollbackResult(connection, "リーダー権限を引き継ぐ権限がありません");
                }
                boolean validTarget;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM groups g JOIN group_members gm ON gm.group_id = g.id "
                                + "WHERE g.id = ? AND g.leader_user_id = ? AND gm.user_id = ? AND gm.role = 'MEMBER'")) {
                    statement.setInt(1, groupId);
                    statement.setInt(2, leaderUserId);
                    statement.setInt(3, targetUserId);
                    try (ResultSet result = statement.executeQuery()) { validTarget = result.next(); }
                }
                if (!validTarget) return rollbackResult(connection, "引き継ぎ先は所属中の一般メンバーに限ります");
                try (PreparedStatement demote = connection.prepareStatement(
                            "UPDATE group_members SET role = 'MEMBER' WHERE group_id = ? AND user_id = ? AND role = 'LEADER'");
                        PreparedStatement promote = connection.prepareStatement(
                            "UPDATE group_members SET role = 'LEADER' WHERE group_id = ? AND user_id = ? AND role = 'MEMBER'");
                        PreparedStatement updateGroup = connection.prepareStatement(
                            "UPDATE groups SET leader_user_id = ? WHERE id = ? AND leader_user_id = ?")) {
                    demote.setInt(1, groupId); demote.setInt(2, leaderUserId);
                    promote.setInt(1, groupId); promote.setInt(2, targetUserId);
                    updateGroup.setInt(1, targetUserId); updateGroup.setInt(2, groupId); updateGroup.setInt(3, leaderUserId);
                    if (demote.executeUpdate() != 1 || promote.executeUpdate() != 1 || updateGroup.executeUpdate() != 1) {
                        throw new SQLException("リーダー変更中にグループの状態が変わりました");
                    }
                }
                connection.commit();
                return new ActionResult(true, "リーダー権限を引き継ぎました");
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static ActionResult leaveGroup(int groupId, int userId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM group_members WHERE group_id = ? AND user_id = ? AND role = 'MEMBER'")) {
            statement.setInt(1, groupId);
            statement.setInt(2, userId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループから脱退しました")
                    : new ActionResult(false, "リーダーは権限を引き継ぐまで脱退できません");
        }
    }

    private static ActionResult deleteGroup(int groupId, int leaderUserId) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM groups WHERE id = ? AND leader_user_id = ?")) {
            statement.setInt(1, groupId);
            statement.setInt(2, leaderUserId);
            return statement.executeUpdate() == 1
                    ? new ActionResult(true, "グループを削除しました")
                    : new ActionResult(false, "グループを削除する権限がありません");
        }
    }

    private static ActionResult inviteToGroup(int groupId, int actorUserId, int targetUserId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String role = findGroupRole(connection, groupId, actorUserId);
                if (role == null) return rollbackResult(connection, "所属していないグループには招待できません");

                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM users WHERE id = ?")) {
                    statement.setInt(1, targetUserId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return rollbackResult(connection, "指定されたユーザーIDは存在しません");
                    }
                }
                if (isGroupMember(connection, groupId, targetUserId)) {
                    return rollbackResult(connection, "そのユーザーはすでに所属しています");
                }
                if (hasPendingRequest(connection, groupId, targetUserId)) {
                    return rollbackResult(connection, "そのユーザーについて有効な招待または参加申請があります");
                }

                if ("LEADER".equals(role)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, 'MEMBER')")) {
                        statement.setInt(1, groupId);
                        statement.setInt(2, targetUserId);
                        statement.executeUpdate();
                    }
                    connection.commit();
                    return new ActionResult(true, "ユーザーをグループへ追加しました");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO group_membership_requests "
                                + "(group_id, type, requester_user_id, target_user_id) "
                                + "VALUES (?, 'MEMBER_INVITE', ?, ?)")) {
                    statement.setInt(1, groupId);
                    statement.setInt(2, actorUserId);
                    statement.setInt(3, targetUserId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new ActionResult(true, "リーダーへ招待の承認依頼を送りました");
            } catch (SQLException e) {
                connection.rollback();
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    return new ActionResult(false, "そのユーザーについて有効な招待または参加申請があります");
                }
                throw e;
            }
        }
    }

    private static ActionResult requestToJoin(int groupId, int userId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                boolean groupExists;
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM groups WHERE id = ?")) {
                    statement.setInt(1, groupId);
                    try (ResultSet result = statement.executeQuery()) { groupExists = result.next(); }
                }
                if (!groupExists) return rollbackResult(connection, "指定されたグループは存在しません");
                if (isGroupMember(connection, groupId, userId)) {
                    return rollbackResult(connection, "そのグループにはすでに所属しています");
                }
                if (hasPendingRequest(connection, groupId, userId)) {
                    return rollbackResult(connection, "このグループへの有効な招待または参加申請があります");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO group_membership_requests "
                                + "(group_id, type, requester_user_id, target_user_id) "
                                + "VALUES (?, 'JOIN_REQUEST', ?, ?)")) {
                    statement.setInt(1, groupId);
                    statement.setInt(2, userId);
                    statement.setInt(3, userId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new ActionResult(true, "参加申請を送りました");
            } catch (SQLException e) {
                connection.rollback();
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    return new ActionResult(false, "このグループへの有効な招待または参加申請があります");
                }
                throw e;
            }
        }
    }

    private static ActionResult decideMembershipRequest(int groupId, int requestId, int leaderUserId, boolean approve)
            throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (!isGroupLeader(connection, groupId, leaderUserId)) {
                    return rollbackResult(connection, "対象の承認依頼がないか、操作する権限がありません");
                }
                Integer targetUserId = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT target_user_id FROM group_membership_requests "
                                + "WHERE id = ? AND group_id = ? AND status = 'PENDING'")) {
                    statement.setInt(1, requestId);
                    statement.setInt(2, groupId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) targetUserId = result.getInt("target_user_id");
                    }
                }
                if (targetUserId == null) {
                    return rollbackResult(connection, "対象の承認依頼がないか、操作する権限がありません");
                }
                if (approve && !isGroupMember(connection, groupId, targetUserId)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, 'MEMBER')")) {
                        statement.setInt(1, groupId);
                        statement.setInt(2, targetUserId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE group_membership_requests SET status = ?, decided_at = CURRENT_TIMESTAMP "
                                + "WHERE id = ? AND status = 'PENDING'")) {
                    statement.setString(1, approve ? "APPROVED" : "REJECTED");
                    statement.setInt(2, requestId);
                    if (statement.executeUpdate() != 1) throw new SQLException("承認状態を更新できませんでした");
                }
                connection.commit();
                return new ActionResult(true, approve ? "承認してメンバーへ追加しました" : "申請を拒否しました");
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static boolean isGroupMember(Connection connection, int groupId, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?")) {
            statement.setInt(1, groupId);
            statement.setInt(2, userId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean hasPendingRequest(Connection connection, int groupId, int targetUserId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM group_membership_requests "
                        + "WHERE group_id = ? AND target_user_id = ? AND status = 'PENDING'")) {
            statement.setInt(1, groupId);
            statement.setInt(2, targetUserId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static ActionResult rollbackResult(Connection connection, String message) throws SQLException {
        connection.rollback();
        return new ActionResult(false, message);
    }

    private static List<MembershipRequest> findPendingRequestsForLeader(int groupId, int leaderUserId)
            throws SQLException {
        List<MembershipRequest> requests = new ArrayList<>();
        String sql = "SELECT r.id, r.group_id, g.name group_name, r.type, r.status, "
                + "requester.username requester_name, target.username target_name "
                + "FROM group_membership_requests r JOIN groups g ON g.id = r.group_id "
                + "JOIN users requester ON requester.id = r.requester_user_id "
                + "JOIN users target ON target.id = r.target_user_id "
                + "WHERE r.group_id = ? AND g.leader_user_id = ? AND r.status = 'PENDING' ORDER BY r.id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, groupId);
            statement.setInt(2, leaderUserId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) requests.add(readMembershipRequest(result));
            }
        }
        return requests;
    }

    private static List<MembershipRequest> findJoinRequestsForUser(int userId) throws SQLException {
        List<MembershipRequest> requests = new ArrayList<>();
        String sql = "SELECT r.id, r.group_id, g.name group_name, r.type, r.status, "
                + "requester.username requester_name, target.username target_name "
                + "FROM group_membership_requests r JOIN groups g ON g.id = r.group_id "
                + "JOIN users requester ON requester.id = r.requester_user_id "
                + "JOIN users target ON target.id = r.target_user_id "
                + "WHERE r.type = 'JOIN_REQUEST' AND r.requester_user_id = ? ORDER BY r.id DESC";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) requests.add(readMembershipRequest(result));
            }
        }
        return requests;
    }

    private static MembershipRequest readMembershipRequest(ResultSet result) throws SQLException {
        return new MembershipRequest(result.getInt("id"), result.getInt("group_id"),
                result.getString("group_name"), result.getString("type"), result.getString("status"),
                result.getString("requester_name"), result.getString("target_name"));
    }

    public static void main(String[] args) throws Exception {
        initializeDatabase(); // ★ 起動時にtodos表を用意
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/todos", exchange -> { // Todo一覧APIの入口を追加する
            try { // APIの処理中に発生する例外を捕捉する
                if (!exchange.getRequestMethod().equals("GET")) { // GET以外のリクエストか確認する
                    sendJson(exchange, 405, "{\"error\":\"Method Not Allowed\"}"); // GET以外には405を返す
                    return; // 以降の処理を中止する
                } // メソッドの確認を終了する
                User user = currentUser(exchange);
                if (user == null) {
                    sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }
                sendJson(exchange, 200, createTodosJson(user.id()));
            } catch (SQLException e) { // データベースエラーを捕捉する
                e.printStackTrace(); // エラーの詳細をサーバー側へ出力する
                sendJson(exchange, 500, "{\"error\":\"Database Error\"}"); // JSON形式で500を返す
            } // 例外処理を終了する
        }); // Todo一覧APIの登録を終了する
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (SQLException e) { // ★ DBエラーをHTTP 500として処理
                e.printStackTrace(); // ★ サーバー側へ原因を表示
                send(exchange, 500, "text/plain", "データベースエラーが発生しました"); // ★ DBエラー応答
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "text/plain", "サーバーエラーが発生しました");
            }
        });
        server.start();
        System.out.println("サーバー起動: http://localhost:8080 （停止: Ctrl+C）");
    }

    private static void handle(HttpExchange exchange) throws IOException, SQLException, GeneralSecurityException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/login") && method.equals("GET")) {
            send(exchange, 200, "text/html", createAuthPage("login", ""));
            return;
        } else if (path.equals("/login") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            User user = authenticate(formValue(body, "username").trim(), formValue(body, "password"));
            if (user == null) {
                send(exchange, 401, "text/html", createAuthPage("login", "ユーザー名またはパスワードが違います"));
            } else {
                startSession(exchange, user);
            }
            return;
        } else if (path.equals("/register") && method.equals("GET")) {
            send(exchange, 200, "text/html", createAuthPage("register", ""));
            return;
        } else if (path.equals("/register") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String username = formValue(body, "username").trim();
            String password = formValue(body, "password");
            String error = validateRegistration(username, password);
            if (error != null) {
                send(exchange, 400, "text/html", createAuthPage("register", error));
            } else {
                User user = registerUser(username, password);
                if (user == null) {
                    send(exchange, 409, "text/html", createAuthPage("register", "そのユーザー名はすでに使われています"));
                } else {
                    startSession(exchange, user);
                }
            }
            return;
        } else if (path.equals("/logout") && method.equals("POST")) {
            endSession(exchange);
            redirectTo(exchange, "/login");
            return;
        }

        User user = currentUser(exchange);
        if (user == null) {
            redirectTo(exchange, "/login");
            return;
        }

        if (path.equals("/home") && method.equals("GET")) {
            send(exchange, 200, "text/html", createHomePage(user));
        } else if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = formValue(body, "todo").trim();
            String deadline = formValue(body, "deadline").trim();
            String category = normalizedCategory(formValue(body, "category"));
            if (!title.isEmpty()) {
                addTodo(user.id(), title, deadline, category); // ★ INSERTで追加
            }
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"), formValue(body, "direction"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/edit") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = formId(body);
            String title = formValue(body, "title").trim();
            if (id != null && !title.isEmpty()) {
                editTodo(user.id(), id, title);
            }
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"), formValue(body, "direction"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                completeTodo(user.id(), id); // ★ UPDATEで完了
            }
            redirect(exchange, queryValue(exchange, "filter"), queryValue(exchange, "sort"), queryValue(exchange, "direction"),
                    queryValue(exchange, "category"));
        } else if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                deleteTodo(user.id(), id); // ★ DELETEで削除
            }
            redirect(exchange, queryValue(exchange, "filter"), queryValue(exchange, "sort"), queryValue(exchange, "direction"),
                    queryValue(exchange, "category"));
        } else if (path.equals("/delete-completed") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deleteCompletedTodos(user.id());
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"), formValue(body, "direction"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/groups") && method.equals("GET")) {
            send(exchange, 200, "text/html", createGroupsPage(user, "", ""));
        } else if (path.equals("/groups/create") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String name = formValue(body, "name").trim();
            String description = formValue(body, "description").trim();
            if (name.isEmpty()) {
                send(exchange, 400, "text/html", createGroupsPage(user, "グループ名を入力してください", ""));
            } else if (name.length() > 100 || description.length() > 1000) {
                send(exchange, 400, "text/html", createGroupsPage(user, "グループ名または説明文が長すぎます", ""));
            } else {
                createGroup(user.id(), name, description);
                redirectTo(exchange, "/groups");
            }
        } else if (path.equals("/groups/join") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            ActionResult result = groupId == null
                    ? new ActionResult(false, "グループIDを正しく入力してください")
                    : requestToJoin(groupId, user.id());
            send(exchange, result.success() ? 200 : 400, "text/html",
                    createGroupsPage(user, result.success() ? "" : result.message(),
                            result.success() ? result.message() : ""));
        } else if (path.equals("/groups/detail") && method.equals("GET")) {
            Integer groupId = queryId(exchange);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) {
                send(exchange, 404, "text/plain", "グループが見つかりません");
            } else {
                send(exchange, 200, "text/html", createGroupDetailPage(user, group, "", ""));
            }
        } else if (path.equals("/groups/settings") && method.equals("GET")) {
            Integer groupId = queryId(exchange);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) {
                send(exchange, 404, "text/plain", "グループが見つかりません");
            } else {
                send(exchange, 200, "text/html", createGroupSettingsPage(user, group, "", ""));
            }
        } else if (path.equals("/group-todos/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formInteger(body, "groupId");
            String title = formValue(body, "title").trim();
            String deadline = formValue(body, "deadline").trim();
            String category = normalizedCategory(formValue(body, "category"));
            ActionResult result = groupId == null || title.isEmpty() || title.length() > 200
                    || !validDeadline(deadline) || category.length() > 100
                    ? new ActionResult(false, "タイトル・締め切り日・カテゴリを正しく入力してください")
                    : addGroupTodo(groupId, user.id(), title, deadline, category);
            sendGroupTodoResult(exchange, user, groupId, result);
        } else if (path.equals("/group-todos/edit") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formInteger(body, "groupId");
            Integer todoId = formInteger(body, "todoId");
            String title = formValue(body, "title").trim();
            String deadline = formValue(body, "deadline").trim();
            String category = normalizedCategory(formValue(body, "category"));
            ActionResult result = groupId == null || todoId == null || title.isEmpty() || title.length() > 200
                    || !validDeadline(deadline) || category.length() > 100
                    ? new ActionResult(false, "編集内容を正しく入力してください")
                    : editGroupTodo(groupId, todoId, user.id(), title, deadline, category);
            sendGroupTodoResult(exchange, user, groupId, result);
        } else if (path.equals("/group-todos/done") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formInteger(body, "groupId");
            Integer todoId = formInteger(body, "todoId");
            ActionResult result = groupId == null || todoId == null
                    ? new ActionResult(false, "グループTodoを正しく指定してください")
                    : toggleGroupTodo(groupId, todoId, user.id());
            sendGroupTodoResult(exchange, user, groupId, result);
        } else if (path.equals("/group-todos/delete") && method.equals("GET")) {
            Integer groupId = queryInteger(exchange, "groupId");
            Integer todoId = queryInteger(exchange, "todoId");
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            Todo todo = group == null || todoId == null ? null : findTodo(findGroupTodos(groupId, user.id()), todoId);
            if (group == null || todo == null || !"LEADER".equals(group.role())) {
                send(exchange, 403, "text/plain", "グループTodoを削除する権限がありません");
            } else {
                send(exchange, 200, "text/html", createGroupTodoDeleteConfirmationPage(user, group, todo));
            }
        } else if (path.equals("/group-todos/delete") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formInteger(body, "groupId");
            Integer todoId = formInteger(body, "todoId");
            ActionResult result = groupId == null || todoId == null
                    ? new ActionResult(false, "グループTodoを正しく指定してください")
                    : deleteGroupTodo(groupId, todoId, user.id());
            sendGroupTodoResult(exchange, user, groupId, result);
        } else if (path.equals("/groups/invite") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) {
                send(exchange, 404, "text/plain", "グループが見つかりません");
            } else {
                Integer targetUserId = formInteger(body, "userId");
                ActionResult result = targetUserId == null || !formValue(body, "userId").matches("[0-9]{8}")
                        ? new ActionResult(false, "ユーザーIDは8桁の数字で入力してください")
                        : inviteToGroup(groupId, user.id(), targetUserId);
                send(exchange, result.success() ? 200 : 400, "text/html",
                        createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                                result.success() ? result.message() : ""));
            }
        } else if (path.equals("/groups/request/decision") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer requestId = formId(body);
            Integer groupId = formInteger(body, "groupId");
            String decision = formValue(body, "decision");
            boolean approve = "approve".equals(decision);
            ActionResult result = requestId == null || groupId == null
                    ? new ActionResult(false, "承認依頼を正しく指定してください")
                    : !(approve || "reject".equals(decision))
                            ? new ActionResult(false, "承認または拒否を正しく指定してください")
                            : decideMembershipRequest(groupId, requestId, user.id(), approve);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) {
                send(exchange, result.success() ? 404 : 403, "text/plain", result.message());
            } else {
                send(exchange, result.success() ? 200 : 403, "text/html",
                        createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                                result.success() ? result.message() : ""));
            }
        } else if (path.equals("/groups/update") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            String name = formValue(body, "name").trim();
            String description = formValue(body, "description").trim();
            ActionResult result;
            if (groupId == null) {
                result = new ActionResult(false, "グループを正しく指定してください");
            } else if (name.isEmpty() || name.length() > 100 || description.length() > 1000) {
                result = new ActionResult(false, "グループ名は1～100文字、説明文は1000文字以内で入力してください");
            } else {
                result = updateGroup(groupId, user.id(), name, description);
            }
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) {
                send(exchange, 403, "text/plain", result.message());
            } else {
                send(exchange, result.success() ? 200 : 403, "text/html",
                        createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                                result.success() ? result.message() : ""));
            }
        } else if (path.equals("/groups/member/remove") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            Integer targetUserId = formInteger(body, "userId");
            ActionResult result = groupId == null || targetUserId == null
                    ? new ActionResult(false, "メンバーを正しく指定してください")
                    : removeGroupMember(groupId, user.id(), targetUserId);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) send(exchange, 403, "text/plain", result.message());
            else send(exchange, result.success() ? 200 : 403, "text/html",
                    createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                            result.success() ? result.message() : ""));
        } else if (path.equals("/groups/invite/cancel") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            Integer requestId = formInteger(body, "requestId");
            ActionResult result = groupId == null || requestId == null
                    ? new ActionResult(false, "招待を正しく指定してください")
                    : cancelGroupInvite(groupId, requestId, user.id());
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) send(exchange, 403, "text/plain", result.message());
            else send(exchange, result.success() ? 200 : 403, "text/html",
                    createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                            result.success() ? result.message() : ""));
        } else if (path.equals("/groups/leader/transfer") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            Integer targetUserId = formInteger(body, "userId");
            ActionResult result = groupId == null || targetUserId == null
                    ? new ActionResult(false, "引き継ぎ先を正しく指定してください")
                    : transferGroupLeadership(groupId, user.id(), targetUserId);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null) send(exchange, 403, "text/plain", result.message());
            else send(exchange, result.success() ? 200 : 403, "text/html",
                    createGroupSettingsPage(user, group, result.success() ? "" : result.message(),
                            result.success() ? result.message() : ""));
        } else if (path.equals("/groups/leave") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            ActionResult result = groupId == null ? new ActionResult(false, "グループを正しく指定してください")
                    : leaveGroup(groupId, user.id());
            if (result.success()) {
                redirectTo(exchange, "/groups");
            } else {
                Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
                if (group == null) send(exchange, 403, "text/plain", result.message());
                else send(exchange, 403, "text/html", createGroupSettingsPage(user, group, result.message(), ""));
            }
        } else if (path.equals("/groups/delete") && method.equals("GET")) {
            Integer groupId = queryId(exchange);
            Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
            if (group == null || !"LEADER".equals(group.role())) {
                send(exchange, 403, "text/plain", "グループを削除する権限がありません");
            } else {
                send(exchange, 200, "text/html", createGroupDeleteConfirmationPage(user, group));
            }
        } else if (path.equals("/groups/delete") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer groupId = formId(body);
            ActionResult result = groupId == null ? new ActionResult(false, "グループを正しく指定してください")
                    : deleteGroup(groupId, user.id());
            if (result.success()) redirectTo(exchange, "/groups");
            else send(exchange, 403, "text/plain", result.message());
        } else if (path.equals("/settings") && method.equals("GET")) {
            send(exchange, 200, "text/html", createSettingsPage(user, "", ""));
        } else if (path.equals("/settings/theme") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String theme = "light".equals(formValue(body, "theme")) ? "light" : "dark";
            changeTheme(user.id(), theme);
            send(exchange, 200, "text/html", createSettingsPage(user, "", "表示テーマを変更しました"));
        } else if (path.equals("/settings/username") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String newUsername = formValue(body, "username").trim();
            if (newUsername.isEmpty() || newUsername.length() > 40) {
                send(exchange, 400, "text/html", createSettingsPage(user, "ユーザー名は1～40文字で入力してください", ""));
            } else if (!changeUsername(user.id(), newUsername)) {
                send(exchange, 409, "text/html", createSettingsPage(user, "そのユーザー名はすでに使われています", ""));
            } else {
                User updatedUser = new User(user.id(), newUsername);
                refreshSessions(updatedUser);
                send(exchange, 200, "text/html", createSettingsPage(updatedUser, "", "ユーザー名を変更しました"));
            }
        } else if (path.equals("/settings/password") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String currentPassword = formValue(body, "currentPassword");
            String newPassword = formValue(body, "newPassword");
            String confirmation = formValue(body, "confirmation");
            String error = null;
            if (!verifyCurrentPassword(user.id(), currentPassword)) {
                error = "現在のパスワードが違います";
            } else if (!newPassword.matches("[0-9]{4}")) {
                error = "新しいパスワードは半角数字4桁で入力してください";
            } else if (!newPassword.equals(confirmation)) {
                error = "新しいパスワードと確認用パスワードが一致しません";
            }
            if (error != null) {
                send(exchange, 400, "text/html", createSettingsPage(user, error, ""));
            } else {
                changePassword(user.id(), newPassword);
                send(exchange, 200, "text/html", createSettingsPage(user, "", "パスワードを変更しました"));
            }
        } else if (path.equals("/settings/delete") && method.equals("GET")) {
            if (leadsAnyGroup(user.id())) {
                send(exchange, 409, "text/html", createSettingsPage(user,
                        "リーダーを引き継ぐかグループを削除するまでアカウントを削除できません", ""));
            } else {
                send(exchange, 200, "text/html", createDeleteConfirmationPage(user));
            }
        } else if (path.equals("/settings/delete") && method.equals("POST")) {
            if (leadsAnyGroup(user.id())) {
                send(exchange, 409, "text/html", createSettingsPage(user,
                        "リーダーを引き継ぐかグループを削除するまでアカウントを削除できません", ""));
            } else {
                deleteAccount(user.id());
                endAllSessions(exchange, user.id());
                redirectTo(exchange, "/login");
            }
        } else if (path.equals("/") && method.equals("GET")) {
            send(exchange, 200, "text/html", createPage(user,
                    queryValue(exchange, "filter"), queryValue(exchange, "sort"), queryValue(exchange, "direction"),
                    queryValue(exchange, "category"), queryId(exchange),
                    queryValue(exchange, "q"))); // ★ 各条件に合う一覧を表示
        } else if (path.equals("/search") && method.equals("GET")) {
            send(exchange, 200, "text/html", createPage(user, queryValue(exchange, "filter"),
                    queryValue(exchange, "sort"), queryValue(exchange, "direction"), queryValue(exchange, "category"), null,
                    queryValue(exchange, "q")));
        } else {
            send(exchange, 404, "text/plain", "ページが見つかりません");
        }
    }

    private static String createPage(User user, String filter, String sort, String direction, String categoryFilter, Integer editId, String keyword) throws SQLException { // ★
                                                                                                       // filterとsortを受け取る
        List<Todo> todos = findAllTodos(user.id()); // ★ ログインユーザーの一覧を読み出す
        List<Todo> displayTodos = new ArrayList<>(); // ★ 元データとは別に表示用一覧を作る
        keyword = keyword == null ? "" : keyword;
        categoryFilter = categoryFilter == null ? "" : categoryFilter;
        if (!"active".equals(filter) && !"done".equals(filter)) {
            filter = "all"; // ★ 指定なし・不明な値は全部として扱う
        }
        if (!"name".equals(sort) && !"deadline".equals(sort)) {
            sort = "added"; // 指定なし・不明な値は追加順として扱う
        }
        direction = "asc".equals(direction) ? "asc" : "desc";
        for (Todo todo : todos) {
            if ((filter.equals("all") || filter.equals("active") && !todo.done()
                    || filter.equals("done") && todo.done()) && todo.title().contains(keyword)
                    && (categoryFilter.isEmpty() || normalizedCategory(todo.category()).equals(categoryFilter))) {
                displayTodos.add(todo); // ★ 条件に一致するTodoだけを表示用一覧へ追加
            }
        }
        boolean ascending = direction.equals("asc");
        if (sort.equals("name")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            displayTodos.sort((a, b) -> {
                int titleOrder = collator.compare(a.title(), b.title());
                return titleOrder != 0 ? (ascending ? titleOrder : -titleOrder) : Integer.compare(b.id(), a.id());
            });
        } else if (sort.equals("deadline")) {
            displayTodos.sort((a, b) -> {
                LocalDate aDeadline = parseDeadline(a.deadline());
                LocalDate bDeadline = parseDeadline(b.deadline());
                if (aDeadline == null && bDeadline == null) {
                    return Integer.compare(b.id(), a.id());
                }
                if (aDeadline == null) {
                    return 1;
                }
                if (bDeadline == null) {
                    return -1;
                }
                int deadlineOrder = aDeadline.compareTo(bDeadline);
                return deadlineOrder != 0 ? (ascending ? deadlineOrder : -deadlineOrder)
                        : Integer.compare(b.id(), a.id());
            });
        } else {
            displayTodos.sort((a, b) -> ascending
                    ? Integer.compare(a.id(), b.id())
                    : Integer.compare(b.id(), a.id()));
        }
        String reverseDirection = ascending ? "desc" : "asc";
        StringBuilder html = new StringBuilder(
                """
                        <!doctype html>
                        <html lang="ja">
                        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Todo</title>
                        <style>
                        *{box-sizing:border-box}html{background:#f6f8fa}body{width:100%;max-width:800px;margin:24px auto;padding:0 20px 32px;color:#24292f;font-family:system-ui,sans-serif;font-size:16px;line-height:1.5}.page-header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}h1{margin:0;font-size:26px;line-height:1.25}.operation-area{margin:0 0 12px;padding:16px;border:1px solid #d0d7de;border-radius:10px;background:#fff}.operation-area h2{margin:0 0 10px;font-size:17px}.operation-area form{display:flex;gap:8px}.add-form .todo-input{flex:3}.add-form .deadline-input{flex:0 1 170px}.add-form .category-input{flex:1.6}.search-form input[name="q"]{flex:1}.filter-nav{display:flex;flex-wrap:wrap;gap:6px}.filter-link{display:inline-block;padding:6px 11px;border:1px solid transparent;border-radius:6px;color:#0969da;text-decoration:none}.filter-link:hover{background:#f3f4f6}.filter-link.selected{border-color:#0969da;background:#0969da;color:#fff;font-weight:600}.filter-controls{display:flex;flex-wrap:wrap;align-items:center;gap:10px 16px;margin-top:12px;padding-top:12px;border-top:1px solid #d8dee4}.filter-controls form{margin:0}.filter-controls label{display:flex;align-items:center;gap:7px;white-space:nowrap}.filter-controls select{min-width:140px}.sort-direction{border:1px solid #0969da;background:#fff;color:#0969da}input,select{min-width:0;height:42px;padding:9px 11px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#24292f;font:inherit}input::placeholder{color:#6e7781;opacity:1}button,.action{display:inline-flex;min-height:42px;align-items:center;justify-content:center;padding:9px 14px;border:0;border-radius:6px;color:#fff;font:inherit;font-weight:600;line-height:1.25;text-decoration:none;white-space:nowrap}button{background:#1f883d;cursor:pointer}.list-header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:18px 0 10px}.todo-count{margin:0;font-weight:600}.bulk-form{flex:none}.bulk-delete{min-height:36px;padding:7px 11px;border:1px solid #cf222e;background:transparent;color:#cf222e;font-size:14px}.bulk-delete:hover{background:#ffebe9}ul{margin:0;padding:0;list-style:none}li{display:flex;align-items:center;gap:12px;margin-bottom:8px;padding:12px 14px;border:1px solid #d0d7de;border-radius:8px;background:#fff}li.overdue{border-color:#cf222e;background:#ffebe9;color:#cf222e}.todo-title,.edit-form{min-width:0;flex:1;overflow-wrap:anywhere}.todo-title.completed{color:#6e7781;text-decoration:line-through}.edit-form{display:flex;gap:8px}.actions{display:flex;flex:none;gap:6px}.done{background:#0969da}.edit{background:#8250df}.delete{background:#cf222e}.empty-state{margin:0;padding:24px;text-align:center;color:#57606a;border:1px dashed #afb8c1;border-radius:8px;background:#fff}#theme-toggle{min-height:34px;padding:6px 10px;border:1px solid #afb8c1;background:#f3f4f6;color:#57606a;font-size:13px;font-weight:500}[data-theme="dark"]{color-scheme:dark;background:#0d1117}[data-theme="dark"] body{color:#e6edf3}[data-theme="dark"] input,[data-theme="dark"] select{border-color:#6e7681;background:#0d1117;color:#e6edf3}[data-theme="dark"] .operation-area,[data-theme="dark"] li,[data-theme="dark"] .empty-state{border-color:#30363d;background:#161b22}[data-theme="dark"] .filter-controls{border-color:#30363d}[data-theme="dark"] .filter-link{color:#58a6ff}[data-theme="dark"] .filter-link:hover{background:#21262d}[data-theme="dark"] .filter-link.selected{border-color:#1f6feb;background:#1f6feb;color:#fff}[data-theme="dark"] li.overdue{border-color:#f85149;background:#3d1515;color:#ff7b72}[data-theme="dark"] button{background:#238636}[data-theme="dark"] .sort-direction{border-color:#58a6ff;background:#161b22;color:#58a6ff}[data-theme="dark"] #theme-toggle{border-color:#30363d;background:#21262d;color:#8b949e}[data-theme="dark"] .bulk-delete{border-color:#f85149;background:transparent;color:#ff7b72}[data-theme="dark"] .bulk-delete:hover{background:#3d1515}[data-theme="dark"] .done{background:#1f6feb}[data-theme="dark"] .edit{background:#8957e5}[data-theme="dark"] .delete{background:#da3633}[data-theme="dark"] .todo-title.completed,[data-theme="dark"] .empty-state{color:#8b949e}@media(max-width:640px){body{margin:16px auto;padding:0 12px 24px}.page-header{margin-bottom:12px}h1{font-size:23px}.operation-area{padding:14px}.operation-area form.add-form{display:grid;grid-template-columns:minmax(0,1fr) minmax(130px,.75fr)}.add-form .todo-input{grid-column:1/-1}.add-form .category-input{min-width:0}.add-form button{grid-column:1/-1}.filter-controls{align-items:stretch;flex-direction:column}.filter-controls form,.filter-controls label,.filter-controls select,.sort-direction{width:100%}li{align-items:stretch;flex-direction:column}.actions{width:100%}.action{flex:1;text-align:center}}@media(max-width:420px){.operation-area form.add-form,.search-form{display:flex;align-items:stretch;flex-direction:column}.add-form .deadline-input{flex:auto}.list-header{align-items:flex-start;flex-direction:column;gap:8px}.bulk-form,.bulk-delete{width:100%}.edit-form{align-items:stretch;flex-direction:column}}
                        </style>
                        <script>document.documentElement.dataset.theme='THEME_VALUE'</script>
                        </head><body><header class="page-header"><h1>
                        """);
        int themeMarker = html.indexOf("THEME_VALUE");
        html.replace(themeMarker, themeMarker + "THEME_VALUE".length(), getUserTheme(user.id()));
        html.append(escapeHtml(user.username())).append("のTodo</h1><span><a class='action done' href='/home'>選択画面</a> <a class='action edit' href='/settings'>設定</a> ")
                .append("<form method='post' action='/logout' style='display:inline'><button type='submit' class='delete'>ログアウト</button></form></span></header>")
                .append("""
                        <section class="operation-area" aria-labelledby="add-heading"><h2 id="add-heading">Todoを追加</h2>
                        <form class="add-form" method="post" action="/add"><input class="todo-input" name="todo" placeholder="やることを入力"><input class="deadline-input" type="date" name="deadline"><input class="category-input" name="category" placeholder="カテゴリ（未入力は未分類）"><button>追加</button>
                        """);
        html.append("<input type='hidden' name='filter' value='").append(filter).append("'>")
                .append("<input type='hidden' name='sort' value='").append(sort).append("'>")
                .append("<input type='hidden' name='direction' value='").append(direction).append("'>")
                .append("<input type='hidden' name='categoryFilter' value='")
                .append(escapeHtml(categoryFilter)).append("'></form></section>")
                .append("<section class='operation-area' aria-labelledby='search-heading'><h2 id='search-heading'>Todoを検索</h2><form class='search-form' method='get' action='/search'>")
                .append("<input name='q' placeholder='キーワードで検索' value='")
                .append(escapeHtml(keyword)).append("'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='direction' value='").append(direction)
                .append("'><input type='hidden' name='category' value='").append(escapeHtml(categoryFilter))
                .append("'><button>検索</button></form></section>")
                .append("<section class='operation-area' aria-labelledby='filter-heading'><h2 id='filter-heading'>表示を絞り込み</h2><nav class='filter-nav'><a class='filter-link").append(filter.equals("all") ? " selected' aria-current='page" : "")
                .append("' href='/?filter=all&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>全部</a>")
                .append("<a class='filter-link").append(filter.equals("active") ? " selected' aria-current='page" : "")
                .append("' href='/?filter=active&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>未完了だけ</a>")
                .append("<a class='filter-link").append(filter.equals("done") ? " selected' aria-current='page" : "")
                .append("' href='/?filter=done&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>完了だけ</a></nav><div class='filter-controls'>")
                .append("<form method='get' action='/'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='category' value='")
                .append(escapeHtml(categoryFilter)).append("'><input type='hidden' name='direction' value='")
                .append(direction).append("'><label>並び順 <select name='sort' onchange=\"this.form.elements.direction.value=this.value==='added'?'desc':'asc';saveScroll();this.form.submit()\">")
                .append("<option value='added'").append(sort.equals("added") ? " selected" : "")
                .append(">追加順</option><option value='deadline'").append(sort.equals("deadline") ? " selected" : "")
                .append(">期限順</option><option value='name'")
                .append(sort.equals("name") ? " selected" : "")
                .append(">名前順</option></select></label></form>")
                .append("<form method='get' action='/'><input type='hidden' name='filter' value='").append(filter)
                .append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='category' value='").append(escapeHtml(categoryFilter))
                .append("'><button class='sort-direction' name='direction' value='").append(reverseDirection)
                .append("' title='並び順を逆にする' aria-label='並び順を逆にする'>↑↓</button></form>")
                .append("<form method='get' action='/'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='direction' value='").append(direction)
                .append("'><label>カテゴリ <select name='category' onchange='saveScroll();this.form.submit()'>")
                .append("<option value=''>すべて</option>");
        String selectedCategory = categoryFilter;
        todos.stream().map(todo -> normalizedCategory(todo.category())).distinct()
                .sorted(Collator.getInstance(Locale.JAPANESE))
                .forEach(category -> html.append("<option value='").append(escapeHtml(category)).append("'")
                        .append(category.equals(selectedCategory) ? " selected" : "").append(">")
                        .append(escapeHtml(category)).append("</option>"));
        html.append("</select></label></form></div></section>");
        long completedCount = todos.stream().filter(Todo::done).count();
        html.append("<div class='list-header'><p class='todo-count'>")
                .append(displayTodos.size()).append("件を表示（全").append(todos.size()).append("件・完了")
                .append(completedCount).append("件）</p><form class='bulk-form' method='post' action='/delete-completed'")
                .append(completedCount > 0
                        ? " onsubmit=\"return confirm('完了済みのTodoをすべて削除しますか？')\""
                        : "")
                .append("><input type='hidden' name='filter' value='").append(filter)
                .append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='direction' value='").append(direction)
                .append("'><input type='hidden' name='categoryFilter' value='")
                .append(escapeHtml(categoryFilter))
                .append("'><button class='bulk-delete' type='submit'>完了済みを一括削除</button></form></div>");
        if (displayTodos.isEmpty()) {
            html.append(keyword.isEmpty()
                    ? "<p class='empty-state'>やることは、いまゼロです</p>"
                    : "<p class='empty-state'>検索結果は0件です。</p>");
        } else {
            html.append("<ul>");
            for (Todo todo : displayTodos) {
                html.append("<li").append(isOverdue(todo) ? " class='overdue'" : "").append(">");
                if (editId != null && editId == todo.id()) {
                    html.append("<form class='edit-form' method='post' action='/edit'>")
                            .append("<input name='title' value='").append(escapeHtml(todo.title()))
                            .append("' required><input type='hidden' name='id' value='").append(todo.id())
                            .append("'><input type='hidden' name='filter' value='").append(filter)
                            .append("'><input type='hidden' name='sort' value='").append(sort)
                            .append("'><input type='hidden' name='direction' value='").append(direction)
                            .append("'><input type='hidden' name='categoryFilter' value='")
                            .append(escapeHtml(categoryFilter))
                            .append("'><button>保存</button></form>");
                } else {
                    html.append("<span class='todo-title").append(todo.done() ? " completed" : "")
                            .append("'>").append(escapeHtml(todo.title())).append("<br>カテゴリ: ")
                            .append(escapeHtml(normalizedCategory(todo.category())))
                            .append("<br>締め切り日: ")
                            .append(todo.deadline() == null || todo.deadline().isEmpty()
                                    ? "未設定"
                                    : escapeHtml(todo.deadline()))
                            .append("</span>");
                }
                html.append("<span class='actions'>")
                        .append("<a class='action edit' href='/?id=").append(todo.id())
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>編集</a>")
                        .append("<a class='action done' href='/done?id=").append(todo.id())
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>完了</a>")
                        .append("<a class='action delete' href='/delete?id=").append(todo.id())
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&direction=").append(direction).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>削除</a></span></li>");
            }
            html.append("</ul>");
        }
        return html.append("<script>const scrollKey='todoScrollY';const savedScrollY=sessionStorage.getItem(scrollKey);if(savedScrollY!==null){sessionStorage.removeItem(scrollKey);window.addEventListener('load',function(){requestAnimationFrame(function(){window.scrollTo(0,Number(savedScrollY));});});}function saveScroll(){sessionStorage.setItem(scrollKey,String(window.scrollY));}document.querySelectorAll('.add-form,.edit-form,.bulk-form,.search-form,.filter-controls form').forEach(function(form){form.addEventListener('submit',function(event){if(!event.defaultPrevented){saveScroll();}});});document.querySelectorAll('a.edit,a.done,a.delete,a.filter-link').forEach(function(link){link.addEventListener('click',function(event){if(!event.defaultPrevented&&event.button===0&&!event.ctrlKey&&!event.metaKey&&!event.shiftKey&&!event.altKey){saveScroll();}});});</script></body></html>").toString();
    }

    private static boolean isOverdue(Todo todo) {
        if (todo.done() || todo.deadline() == null || todo.deadline().isEmpty()) {
            return false;
        }
        try {
            return LocalDate.parse(todo.deadline()).isBefore(LocalDate.now());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static LocalDate parseDeadline(String deadline) {
        if (deadline == null || deadline.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(deadline);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String validateRegistration(String username, String password) {
        if (username.isEmpty() || username.length() > 40) {
            return "ユーザー名は1～40文字で入力してください";
        }
        if (!password.matches("[0-9]{4}")) {
            return "パスワードは半角数字4桁で入力してください";
        }
        return null;
    }

    private static boolean changeUsername(int userId, String username) throws SQLException {
        String sql = "UPDATE users SET username = ? WHERE id = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setInt(2, userId);
            try {
                return statement.executeUpdate() == 1;
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    return false;
                }
                throw e;
            }
        }
    }

    private static boolean verifyCurrentPassword(int userId, String password)
            throws SQLException, GeneralSecurityException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT password_hash FROM users WHERE id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && verifyPassword(password, result.getString("password_hash"));
            }
        }
    }

    private static void changePassword(int userId, String password)
            throws SQLException, GeneralSecurityException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET password_hash = ? WHERE id = ?")) {
            statement.setString(1, hashPassword(password));
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
    }

    private static String getUserTheme(int userId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("SELECT theme FROM users WHERE id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "light".equals(result.getString("theme")) ? "light" : "dark";
            }
        }
    }

    private static void changeTheme(int userId, String theme) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("UPDATE users SET theme = ? WHERE id = ?")) {
            statement.setString(1, "light".equals(theme) ? "light" : "dark");
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
    }

    private static void deleteAccount(int userId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteTodos = connection.prepareStatement(
                    "DELETE FROM todos WHERE user_id = ?");
                    PreparedStatement deleteUser = connection.prepareStatement(
                            "DELETE FROM users WHERE id = ?")) {
                deleteTodos.setInt(1, userId);
                deleteTodos.executeUpdate();
                deleteUser.setInt(1, userId);
                if (deleteUser.executeUpdate() != 1) {
                    throw new SQLException("削除対象のユーザーが見つかりません");
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static User registerUser(String username, String password)
            throws SQLException, GeneralSecurityException {
        String sql = "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int userId = generateAvailableUserId(connection);
            statement.setInt(1, userId);
            statement.setString(2, username);
            statement.setString(3, hashPassword(password));
            try {
                statement.executeUpdate();
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    return null;
                }
                throw e;
            }
            return new User(userId, username);
        }
    }

    private static User authenticate(String username, String password)
            throws SQLException, GeneralSecurityException {
        String sql = "SELECT id, username, password_hash FROM users WHERE username = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && verifyPassword(password, result.getString("password_hash"))) {
                    return new User(result.getInt("id"), result.getString("username"));
                }
            }
        }
        return null;
    }

    private static String hashPassword(String password) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derivePassword(password, salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String stored) throws GeneralSecurityException {
        try {
            String[] values = stored.split(":", 2);
            byte[] salt = Base64.getDecoder().decode(values[0]);
            byte[] expected = Base64.getDecoder().decode(values[1]);
            return MessageDigest.isEqual(expected, derivePassword(password, salt));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    private static byte[] derivePassword(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static User currentUser(HttpExchange exchange) {
        String token = cookieValue(exchange, "session");
        return token == null ? null : SESSIONS.get(token);
    }

    private static void refreshSessions(User user) {
        SESSIONS.replaceAll((token, sessionUser) -> sessionUser.id() == user.id() ? user : sessionUser);
    }

    private static void startSession(HttpExchange exchange, User user) throws IOException {
        String token = UUID.randomUUID().toString();
        SESSIONS.put(token, user);
        exchange.getResponseHeaders().add("Set-Cookie",
                "session=" + token + "; Path=/; HttpOnly; SameSite=Lax");
        redirectTo(exchange, "/home");
    }

    private static void endSession(HttpExchange exchange) {
        String token = cookieValue(exchange, "session");
        if (token != null) {
            SESSIONS.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie",
                "session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static void endAllSessions(HttpExchange exchange, int userId) {
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().id() == userId);
        exchange.getResponseHeaders().add("Set-Cookie",
                "session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String cookie : header.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static String createAuthPage(String mode, String error) {
        boolean register = "register".equals(mode);
        String title = register ? "新規登録" : "ログイン";
        String otherPath = register ? "/login" : "/register";
        String otherText = register ? "ログインはこちら" : "新規登録はこちら";
        return "<!doctype html><html lang='ja' data-theme='dark'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>" + title + "</title>"
                + "<style>*{box-sizing:border-box}html{color-scheme:dark;background:#0d1117}body{max-width:420px;margin:60px auto;padding:20px;font-family:system-ui,sans-serif;color:#e6edf3}main{padding:24px;border:1px solid #30363d;border-radius:10px;background:#161b22}h1{margin-top:0}label{display:block;margin:14px 0 5px}input,button{width:100%;height:44px;padding:9px 11px;font:inherit;border-radius:6px}input{border:1px solid #6e7681;background:#0d1117;color:#e6edf3}button{margin-top:18px;border:0;background:#238636;color:white;font-weight:600;cursor:pointer}.error{padding:10px;border-radius:6px;background:#3d1515;color:#ff7b72}a{color:#58a6ff}</style>"
                + "</head><body><main><h1>" + title + "</h1>"
                + (error.isEmpty() ? "" : "<p class='error'>" + escapeHtml(error) + "</p>")
                + "<form method='post' action='" + (register ? "/register" : "/login") + "'>"
                + "<label for='username'>ユーザー名</label><input id='username' name='username' maxlength='40' required autocomplete='username'>"
                + "<label for='password'>4桁のパスワード</label><input id='password' name='password' type='password' inputmode='numeric' pattern='[0-9]{4}' maxlength='4' required autocomplete='"
                + (register ? "new-password" : "current-password") + "'><button>" + title + "</button></form>"
                + "<p><a href='" + otherPath + "'>" + otherText + "</a></p></main></body></html>";
    }

    private static String createSettingsPage(User user, String error, String message) throws SQLException {
        String theme = getUserTheme(user.id());
        return settingsPageStart("アカウント設定", theme)
                + "<header><h1>アカウント設定</h1><a href='/home'>選択画面へ戻る</a></header>"
                + (error.isEmpty() ? "" : "<p class='notice error'>" + escapeHtml(error) + "</p>")
                + (message.isEmpty() ? "" : "<p class='notice success'>" + escapeHtml(message) + "</p>")
                + "<section><h2>ユーザーID</h2><p><strong>" + String.format("%08d", user.id())
                + "</strong></p><p>グループへ招待してもらうときに、この8桁のIDを伝えてください。</p></section>"
                + "<section><h2>表示設定</h2><form id='theme-form' method='post' action='/settings/theme'>"
                + "<label for='theme'>テーマ</label><select id='theme' name='theme'>"
                + "<option value='dark'" + (theme.equals("dark") ? " selected" : "") + ">ダーク</option>"
                + "<option value='light'" + (theme.equals("light") ? " selected" : "") + ">ライト</option>"
                + "</select><button>表示設定を保存</button><p id='theme-status' class='theme-status' aria-live='polite'></p></form></section>"
                + "<section><h2>ユーザー名の変更</h2>"
                + "<form method='post' action='/settings/username'>"
                + "<label for='username'>新しいユーザー名</label>"
                + "<input id='username' name='username' value='" + escapeHtml(user.username())
                + "' maxlength='40' required autocomplete='username'><button>ユーザー名を変更</button></form></section>"
                + "<section><h2>パスワードの変更</h2>"
                + "<form method='post' action='/settings/password'>"
                + "<label for='currentPassword'>現在のパスワード</label>"
                + passwordInput("currentPassword", "current-password")
                + "<label for='newPassword'>新しいパスワード（半角数字4桁）</label>"
                + passwordInput("newPassword", "new-password")
                + "<label for='confirmation'>新しいパスワード（確認）</label>"
                + passwordInput("confirmation", "new-password")
                + "<button>パスワードを変更</button></form></section>"
                + "<section class='danger-zone'><h2>アカウントの削除</h2>"
                + "<p>ユーザー情報と、このアカウントのTodoがすべて削除されます。</p>"
                + "<a class='button danger' href='/settings/delete'>削除の確認へ</a></section>"
                + "<script>const themeSelect=document.getElementById('theme');const themeStatus=document.getElementById('theme-status');themeSelect.addEventListener('change',async function(){const previous=document.documentElement.dataset.theme;document.documentElement.dataset.theme=this.value;themeStatus.textContent='保存中…';try{const response=await fetch('/settings/theme',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({theme:this.value})});if(!response.ok)throw new Error();themeStatus.textContent='表示設定を保存しました';}catch(error){document.documentElement.dataset.theme=previous;this.value=previous;themeStatus.textContent='保存できませんでした。もう一度お試しください';}});</script></main></body></html>";
    }

    private static String createHomePage(User user) throws SQLException {
        List<Todo> personalTodos = findAllTodos(user.id());
        List<Group> groups = findGroupsForUser(user.id());
        StringBuilder html = new StringBuilder(settingsPageStart("Todoの選択", getUserTheme(user.id())))
                .append("<header class='page-header'><div><h1>").append(escapeHtml(user.username()))
                .append("さんのTodo</h1><p class='page-description'>表示するリストを選んでください</p></div>")
                .append("<div class='header-actions'><a class='button secondary' href='/settings'>設定</a>")
                .append("<form method='post' action='/logout'><button class='danger' type='submit'>ログアウト</button></form></div></header>")
                .append("<div class='list-choices'><section><h2>個人</h2>")
                .append("<a class='list-choice' href='/'><span><strong>個人Todo</strong><small>")
                .append(personalTodos.size()).append("件のTodo</small></span><span aria-hidden='true'>›</span></a></section>")
                .append("<section><div class='section-heading'><h2>所属グループ</h2>")
                .append("<a href='/groups'>グループの作成・参加</a></div>");
        if (groups.isEmpty()) {
            html.append("<p class='empty-choice'>所属しているグループはありません。</p>");
        } else {
            for (Group group : groups) {
                int todoCount = findGroupTodos(group.id(), user.id()).size();
                html.append("<a class='list-choice' href='/groups/detail?id=").append(group.id())
                        .append("'>")
                        .append("<span><strong>").append(escapeHtml(group.name())).append("</strong><small>")
                        .append(todoCount).append("件のTodo・")
                        .append("LEADER".equals(group.role()) ? "リーダー" : "一般メンバー")
                        .append("</small></span><span aria-hidden='true'>›</span></a>");
            }
        }
        return html.append("</section></div></main></body></html>").toString();
    }

    private static String createGroupsPage(User user, String error, String message) throws SQLException {
        StringBuilder html = new StringBuilder(settingsPageStart("グループ", getUserTheme(user.id())))
                .append("<header class='page-header'><h1>").append(escapeHtml(user.username()))
                .append("のグループ</h1>").append(accountNavigation()).append("</header>")
                .append("<nav class='page-menu' aria-label='グループメニュー'>")
                .append("<a href='#groups'>所属グループ</a><a href='#create'>新規作成</a>")
                .append("<a href='#join'>参加申請</a><a href='#requests'>申請状況</a></nav>");
        if (!error.isEmpty()) {
            html.append("<p class='notice error'>").append(escapeHtml(error)).append("</p>");
        }
        if (!message.isEmpty()) {
            html.append("<p class='notice success'>").append(escapeHtml(message)).append("</p>");
        }
        html.append("<section id='create'><h2>新しいグループを作成</h2>"
                + "<form method='post' action='/groups/create'>"
                + "<label for='group-name'>グループ名（必須）</label>"
                + "<input id='group-name' name='name' maxlength='100' required>"
                + "<label for='group-description'>説明文（任意）</label>"
                + "<textarea id='group-description' name='description' maxlength='1000' "
                + "style='width:100%;min-height:100px;padding:9px 11px;font:inherit'></textarea>"
                + "<button>作成</button></form></section>"
                + "<section id='join'><h2>グループへ参加申請</h2>"
                + "<form method='post' action='/groups/join'>"
                + "<label for='join-group-id'>参加するグループID</label>"
                + "<input id='join-group-id' name='id' type='number' min='1' required>"
                + "<button>参加申請を送る</button></form></section>"
                + "<section id='groups'><h2>所属グループ</h2>");
        List<Group> groups = findGroupsForUser(user.id());
        if (groups.isEmpty()) {
            html.append("<p>所属しているグループはありません。</p>");
        } else {
            html.append("<ul>");
            for (Group group : groups) {
                html.append("<li><a href='/groups/detail?id=").append(group.id()).append("'>")
                        .append(escapeHtml(group.name())).append("</a> — ID: ").append(group.id())
                        .append("（").append("LEADER".equals(group.role()) ? "リーダー" : "一般メンバー")
                        .append("）</li>");
            }
            html.append("</ul>");
        }
        html.append("</section><section id='requests'><h2>自分の参加申請状況</h2>");
        List<MembershipRequest> joinRequests = findJoinRequestsForUser(user.id());
        if (joinRequests.isEmpty()) {
            html.append("<p>参加申請はありません。</p>");
        } else {
            html.append("<ul>");
            for (MembershipRequest request : joinRequests) {
                html.append("<li>").append(escapeHtml(request.groupName())).append("（ID: ")
                        .append(request.groupId()).append("）— ").append(statusLabel(request.status())).append("</li>");
            }
            html.append("</ul>");
        }
        return html.append("</section></main></body></html>").toString();
    }

    private static String createGroupDetailPage(User user, Group group, String error, String message)
            throws SQLException {
        boolean leader = "LEADER".equals(group.role());
        List<Todo> groupTodos = findGroupTodos(group.id(), user.id());
        StringBuilder html = new StringBuilder(settingsPageStart(group.name() + "のTodo", getUserTheme(user.id())))
                .append("<header class='page-header'><h1>").append(escapeHtml(group.name()))
                .append("のTodo</h1><div class='header-actions'><a class='button' href='/home'>選択画面</a>")
                .append("<a class='button secondary' href='/groups/settings?id=").append(group.id())
                .append("'>グループ設定</a><form method='post' action='/logout'><button class='danger'>ログアウト</button></form></div></header>");
        if (!error.isEmpty()) html.append("<p class='notice error'>").append(escapeHtml(error)).append("</p>");
        if (!message.isEmpty()) html.append("<p class='notice success'>").append(escapeHtml(message)).append("</p>");
        if (leader) {
            html.append("<section><h2>Todoを追加</h2><form method='post' action='/group-todos/add'>")
                    .append("<input type='hidden' name='groupId' value='").append(group.id()).append("'>")
                    .append("<label>やること</label><input name='title' maxlength='200' required>")
                    .append("<label>締め切り日</label><input name='deadline' type='date'>")
                    .append("<label>カテゴリ</label><input name='category' maxlength='100' placeholder='未入力は未分類'>")
                    .append("<button>追加</button></form></section>");
        }
        html.append("<section><h2>Todo一覧（").append(groupTodos.size()).append("件）</h2>");
        if (groupTodos.isEmpty()) {
            html.append("<p>やることは、いまゼロです</p>");
        } else {
            html.append("<ul>");
            for (Todo todo : groupTodos) {
                html.append("<li><span class='todo-title'><strong>")
                        .append(todo.done() ? "完了: " : "未完了: ").append(escapeHtml(todo.title()))
                        .append("</strong><br>カテゴリ: ").append(escapeHtml(normalizedCategory(todo.category())))
                        .append("<br>締め切り日: ")
                        .append(todo.deadline() == null || todo.deadline().isEmpty() ? "未設定" : escapeHtml(todo.deadline()))
                        .append("</span><form method='post' action='/group-todos/done'>")
                        .append("<input type='hidden' name='groupId' value='").append(group.id()).append("'>")
                        .append("<input type='hidden' name='todoId' value='").append(todo.id()).append("'>")
                        .append("<button>").append(todo.done() ? "未完了に戻す" : "完了").append("</button></form>");
                if (leader) {
                    html.append("<details><summary>編集</summary><form method='post' action='/group-todos/edit'>")
                            .append("<input type='hidden' name='groupId' value='").append(group.id()).append("'>")
                            .append("<input type='hidden' name='todoId' value='").append(todo.id()).append("'>")
                            .append("<label>やること</label><input name='title' maxlength='200' required value='")
                            .append(escapeHtml(todo.title())).append("'><label>締め切り日</label><input name='deadline' type='date' value='")
                            .append(escapeHtml(todo.deadline() == null ? "" : todo.deadline()))
                            .append("'><label>カテゴリ</label><input name='category' maxlength='100' value='")
                            .append(escapeHtml(normalizedCategory(todo.category())))
                            .append("'><button>保存</button></form></details><a class='button danger' href='/group-todos/delete?groupId=")
                            .append(group.id()).append("&todoId=").append(todo.id()).append("'>削除</a>");
                }
                html.append("</li>");
            }
            html.append("</ul>");
        }
        return html.append("</section></main></body></html>").toString();
    }

    private static String createGroupSettingsPage(User user, Group group, String error, String message)
            throws SQLException {
        StringBuilder html = new StringBuilder(settingsPageStart(group.name(), getUserTheme(user.id())))
                .append("<header class='page-header'><h1>").append(escapeHtml(group.name()))
                .append("</h1>").append(accountNavigation()).append("</header>")
                .append("<nav class='page-menu' aria-label='グループ設定メニュー'>")
                .append("<a href='/groups/detail?id=").append(group.id()).append("'>Todo一覧</a><a href='/groups'>グループ一覧</a>")
                .append("<a href='#members'>メンバー</a><a href='#invite'>招待</a>")
                .append("LEADER".equals(group.role())
                        ? "<a href='#group-settings'>グループ管理</a></nav>"
                        : "<a href='#overview'>グループ情報</a></nav>");
        if (!error.isEmpty()) html.append("<p class='notice error'>").append(escapeHtml(error)).append("</p>");
        if (!message.isEmpty()) html.append("<p class='notice success'>").append(escapeHtml(message)).append("</p>");
        html.append("<section id='overview'><h2>グループ情報</h2><p><strong>グループID:</strong> ")
                .append(group.id()).append("</p><p><strong>説明:</strong> ")
                .append(group.description().isEmpty() ? "（説明なし）" : escapeHtml(group.description()))
                .append("</p><p><strong>あなたの役職:</strong> ")
                .append("LEADER".equals(group.role()) ? "リーダー" : "一般メンバー")
                .append("</p></section>");
        if ("LEADER".equals(group.role())) {
            html.append("<section id='group-settings'><h2>グループ情報を変更</h2><form method='post' action='/groups/update'>")
                    .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                    .append("<label for='edit-group-name'>グループ名</label>")
                    .append("<input id='edit-group-name' name='name' maxlength='100' required value='")
                    .append(escapeHtml(group.name())).append("'>")
                    .append("<label for='edit-group-description'>説明文</label>")
                    .append("<textarea id='edit-group-description' name='description' maxlength='1000' ")
                    .append("style='width:100%;min-height:100px;padding:9px 11px;font:inherit'>")
                    .append(escapeHtml(group.description())).append("</textarea><button>変更を保存</button></form></section>");
        }
        List<GroupMember> members = findGroupMembers(group.id(), user.id());
        html.append("<section id='members'><h2>メンバー一覧</h2><ul>");
        for (GroupMember member : members) {
            html.append("<li><strong>").append(escapeHtml(member.username())).append("</strong>（ID: ")
                    .append(member.userId()).append("）— ")
                    .append("LEADER".equals(member.role()) ? "リーダー" : "一般メンバー");
            if ("LEADER".equals(group.role()) && "MEMBER".equals(member.role())) {
                html.append("<form method='post' action='/groups/member/remove'>")
                        .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                        .append("<input type='hidden' name='userId' value='").append(member.userId()).append("'>")
                        .append("<button class='danger'>メンバーから削除</button></form>");
            }
            html.append("</li>");
        }
        html.append("</ul></section><section id='invite'><h2>ユーザーを招待</h2>")
                .append("<p>").append("LEADER".equals(group.role())
                        ? "リーダーからの招待は、すぐにメンバーへ追加されます。"
                        : "一般メンバーからの招待は、リーダーの承認後に追加されます。")
                .append("</p><form method='post' action='/groups/invite'>")
                .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                .append("<label for='invite-user-id'>招待するユーザーID（8桁）</label>")
                .append("<input id='invite-user-id' name='userId' inputmode='numeric' pattern='[0-9]{8}' maxlength='8' required>")
                .append("<button>招待する</button></form></section>");
        if ("LEADER".equals(group.role())) {
            html.append("<section><h2>承認・拒否する依頼</h2>");
            List<MembershipRequest> requests = findPendingRequestsForLeader(group.id(), user.id());
            if (requests.isEmpty()) {
                html.append("<p>保留中の依頼はありません。</p>");
            } else {
                html.append("<ul>");
                for (MembershipRequest request : requests) {
                    html.append("<li><strong>")
                            .append("MEMBER_INVITE".equals(request.type()) ? "一般メンバーによる招待" : "ユーザー本人の参加申請")
                            .append("</strong><br>");
                    if ("MEMBER_INVITE".equals(request.type())) {
                        html.append("招待者: ").append(escapeHtml(request.requesterName())).append(" / ");
                    }
                    html.append("参加対象: ").append(escapeHtml(request.targetName()))
                            .append("<form method='post' action='/groups/request/decision'>")
                            .append("<input type='hidden' name='id' value='").append(request.id()).append("'>")
                            .append("<input type='hidden' name='groupId' value='").append(group.id()).append("'>")
                            .append("<button name='decision' value='approve'>承認</button> ")
                            .append("<button class='danger' name='decision' value='reject'>拒否</button></form>");
                    if ("MEMBER_INVITE".equals(request.type())) {
                        html.append("<form method='post' action='/groups/invite/cancel'>")
                                .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                                .append("<input type='hidden' name='requestId' value='").append(request.id()).append("'>")
                                .append("<button class='danger'>招待を取り消す</button></form>");
                    }
                    html.append("</li>");
                }
                html.append("</ul>");
            }
            html.append("</section>");
            html.append("<section><h2>リーダーを変更</h2><p>引き継ぎ後、あなたは一般メンバーになります。</p>")
                    .append("<form method='post' action='/groups/leader/transfer'>")
                    .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                    .append("<label for='new-leader'>引き継ぎ先</label><select id='new-leader' name='userId' required>")
                    .append("<option value=''>一般メンバーを選択</option>");
            for (GroupMember member : members) {
                if ("MEMBER".equals(member.role())) {
                    html.append("<option value='").append(member.userId()).append("'>")
                            .append(escapeHtml(member.username())).append("（ID: ").append(member.userId())
                            .append("）</option>");
                }
            }
            html.append("</select><button>リーダー権限を引き継ぐ</button></form></section>")
                    .append("<section class='danger-zone'><h2>グループを削除</h2>")
                    .append("<p>メンバー・招待・参加申請もすべて削除されます。</p>")
                    .append("<a class='button danger' href='/groups/delete?id=").append(group.id())
                    .append("'>削除の確認へ</a></section>");
        } else {
            html.append("<section class='danger-zone'><h2>グループから脱退</h2>")
                    .append("<form method='post' action='/groups/leave'>")
                    .append("<input type='hidden' name='id' value='").append(group.id()).append("'>")
                    .append("<button class='danger'>脱退する</button></form></section>");
        }
        return html.append("</main></body></html>").toString();
    }

    private static String createGroupDeleteConfirmationPage(User user, Group group) throws SQLException {
        return settingsPageStart("グループ削除の確認", getUserTheme(user.id()))
                + "<section class='danger-zone'><h1>グループを削除しますか？</h1>"
                + "<p><strong>" + escapeHtml(group.name())
                + "</strong> と、関連するメンバー・招待・参加申請情報は元に戻せません。</p>"
                + "<div class='confirmation-actions'><a class='button secondary' href='/groups/detail?id="
                + group.id() + "'>キャンセル</a><form method='post' action='/groups/delete'>"
                + "<input type='hidden' name='id' value='" + group.id() + "'>"
                + "<button class='danger'>グループを削除する</button></form></div></section></main></body></html>";
    }

    private static String createGroupTodoDeleteConfirmationPage(User user, Group group, Todo todo)
            throws SQLException {
        return settingsPageStart("グループTodo削除の確認", getUserTheme(user.id()))
                + "<section class='danger-zone'><h1>グループTodoを削除しますか？</h1><p><strong>"
                + escapeHtml(todo.title()) + "</strong> は元に戻せません。</p>"
                + "<div class='confirmation-actions'><a class='button secondary' href='/groups/detail?id="
                + group.id() + "'>キャンセル</a><form method='post' action='/group-todos/delete'>"
                + "<input type='hidden' name='groupId' value='" + group.id() + "'>"
                + "<input type='hidden' name='todoId' value='" + todo.id() + "'>"
                + "<button class='danger'>グループTodoを削除する</button></form></div></section></main></body></html>";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "APPROVED" -> "承認済み";
            case "REJECTED" -> "拒否";
            default -> "承認待ち";
        };
    }

    private static String passwordInput(String name, String autocomplete) {
        return "<input id='" + name + "' name='" + name
                + "' type='password' inputmode='numeric' pattern='[0-9]{4}' maxlength='4' required autocomplete='"
                + autocomplete + "'>";
    }

    private static String createDeleteConfirmationPage(User user) throws SQLException {
        return settingsPageStart("アカウント削除の確認", getUserTheme(user.id()))
                + "<section class='danger-zone'><h1>アカウントを削除しますか？</h1>"
                + "<p><strong>" + escapeHtml(user.username()) + "</strong> のユーザー情報とTodoは元に戻せません。</p>"
                + "<div class='confirmation-actions'><a class='button secondary' href='/settings'>キャンセル</a>"
                + "<form method='post' action='/settings/delete'><button class='danger'>削除する</button></form></div>"
                + "</section></main></body></html>";
    }

    private static String settingsPageStart(String title, String theme) {
        return "<!doctype html><html lang='ja' data-theme='" + theme + "'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>" + title + "</title>"
                + "<style>*{box-sizing:border-box}html{background:#f6f8fa;scroll-behavior:smooth}body{max-width:800px;margin:24px auto;padding:0 20px 32px;font-family:system-ui,sans-serif;color:#24292f;line-height:1.5}main>header{display:flex;align-items:center;justify-content:space-between;gap:16px}h1{font-size:26px}.header-actions{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:6px}.header-actions form{margin:0}.header-actions .button,.header-actions button{min-height:42px;margin:0}.page-menu{display:flex;flex-wrap:wrap;gap:6px;margin:16px 0}.page-menu a{display:inline-flex;min-height:38px;align-items:center;padding:7px 12px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#0969da;font-weight:600;text-decoration:none}.page-menu a:hover{border-color:#0969da;background:#f3f4f6}h2{margin-top:0;font-size:18px}section{margin:12px 0;padding:18px;border:1px solid #d0d7de;border-radius:10px;background:#fff;scroll-margin-top:12px}label{display:block;margin:12px 0 5px}input,select{width:100%;height:42px;padding:9px 11px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#24292f;font:inherit}button,.button{display:inline-flex;min-height:42px;align-items:center;justify-content:center;margin-top:14px;padding:9px 14px;border:0;border-radius:6px;background:#1f883d;color:#fff;font:inherit;font-weight:600;text-decoration:none;cursor:pointer}.danger-zone{border-color:#cf222e}.danger{background:#cf222e}.secondary{border:1px solid #afb8c1;background:#fff;color:#24292f}.notice{padding:10px;border-radius:6px}.error{background:#ffebe9;color:#cf222e}.success{background:#dafbe1;color:#116329}.theme-status{min-height:24px;margin:8px 0 0;color:#57606a}.confirmation-actions{display:flex;gap:10px}.confirmation-actions form{margin:0}.confirmation-actions .button,.confirmation-actions button{margin-top:8px}[data-theme='dark']{color-scheme:dark;background:#0d1117}[data-theme='dark'] body{color:#e6edf3}[data-theme='dark'] section,[data-theme='dark'] .page-menu a{border-color:#30363d;background:#161b22}[data-theme='dark'] .page-menu a{color:#58a6ff}[data-theme='dark'] .page-menu a:hover{border-color:#58a6ff;background:#21262d}[data-theme='dark'] input,[data-theme='dark'] select{border-color:#6e7681;background:#0d1117;color:#e6edf3}[data-theme='dark'] a{color:#58a6ff}[data-theme='dark'] .secondary{border-color:#6e7681;background:#21262d;color:#e6edf3}[data-theme='dark'] .danger-zone{border-color:#f85149}[data-theme='dark'] .error{background:#3d1515;color:#ff7b72}[data-theme='dark'] .success{background:#12261e;color:#56d364}[data-theme='dark'] .theme-status{color:#8b949e}@media(max-width:600px){body{margin:16px auto;padding:0 12px 24px}main>header{align-items:flex-start;flex-direction:column}.header-actions{width:100%;justify-content:flex-start}.header-actions .button,.header-actions form,.header-actions button{flex:1}.page-menu{display:grid;grid-template-columns:1fr 1fr}.page-menu a{justify-content:center}.confirmation-actions{align-items:stretch;flex-direction:column}.confirmation-actions form,.confirmation-actions button,.confirmation-actions .button{width:100%}}</style>"
                + "<style>.page-description{margin:4px 0 0;color:#57606a}.list-choices{display:grid;gap:12px;margin-top:18px}.list-choices section{margin:0}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.list-choice{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:8px;padding:14px;border:1px solid #d0d7de;border-radius:8px;color:#24292f;text-decoration:none}.list-choice:hover{border-color:#0969da;background:#f6f8fa}.list-choice span:first-child{display:grid;gap:3px}.list-choice small{color:#57606a}.list-choice span:last-child{color:#0969da;font-size:28px;line-height:1}.empty-choice{margin-bottom:0;color:#57606a}[data-theme='dark'] .page-description,[data-theme='dark'] .list-choice small,[data-theme='dark'] .empty-choice{color:#8b949e}[data-theme='dark'] .list-choice{border-color:#30363d;color:#e6edf3}[data-theme='dark'] .list-choice:hover{border-color:#58a6ff;background:#21262d}@media(max-width:600px){.section-heading{align-items:flex-start;flex-direction:column}}</style>"
                + "</head><body><main>";
    }

    private static String accountNavigation() {
        return "<div class='header-actions'><a class='button' href='/home'>選択画面</a>"
                + "<a class='button' href='/'>個人Todo</a>"
                + "<a class='button secondary' href='/settings'>設定</a>"
                + "<form method='post' action='/logout'><button class='danger' type='submit'>ログアウト</button></form></div>";
    }

    private static void redirectTo(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static String formValue(String body, String name) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    private static Integer queryId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null)
            return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals("id")) {
                try {
                    return Integer.parseInt(pair[1]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Integer queryInteger(HttpExchange exchange, String name) {
        try {
            String value = queryValue(exchange, name);
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer formId(String body) {
        return formInteger(body, "id");
    }

    private static Integer formInteger(String body, String name) {
        try {
            return Integer.parseInt(formValue(body, name));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String queryValue(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null)
            return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) {
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String normalizedCategory(String category) {
        return category == null || category.trim().isEmpty() ? "未分類" : category.trim();
    }

    private static boolean validDeadline(String deadline) {
        if (deadline == null || deadline.isEmpty()) return true;
        try {
            LocalDate.parse(deadline);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static Todo findTodo(List<Todo> todos, int id) {
        for (Todo todo : todos) if (todo.id() == id) return todo;
        return null;
    }

    private static void sendGroupTodoResult(HttpExchange exchange, User user, Integer groupId,
            ActionResult result) throws IOException, SQLException {
        Group group = groupId == null ? null : findGroupForMember(groupId, user.id());
        if (group == null) {
            send(exchange, 403, "text/plain", "このグループへアクセスできません");
            return;
        }
        send(exchange, result.success() ? 200 : 403, "text/html",
                createGroupDetailPage(user, group, result.success() ? "" : result.message(),
                        result.success() ? result.message() : ""));
    }

    private static String encodeUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String createTodosJson(int userId) throws SQLException { // ユーザーのTodoのJSON文字列を作成する
        List<Todo> todos = findAllTodos(userId); // データベースから対象ユーザーのTodoを取得する
        StringBuilder json = new StringBuilder("["); // JSON配列の開始記号を追加する
        for (int i = 0; i < todos.size(); i++) { // Todoを先頭から順番に処理する
            if (i > 0) { // 先頭以外のTodoか確認する
                json.append(","); // Todo同士を区切るカンマを追加する
            } // カンマの追加判定を終了する
            Todo todo = todos.get(i); // 現在のTodoを取得する
            json.append("{\"title\":\""); // titleプロパティの開始部分を追加する
            json.append(escapeJson(todo.title())); // タイトルをJSON用にエスケープして追加する
            json.append("\",\"done\":"); // doneプロパティの開始部分を追加する
            json.append(todo.done()); // 完了状態を真偽値として追加する
            json.append(",\"category\":\"").append(escapeJson(normalizedCategory(todo.category()))).append("\"");
            json.append("}"); // Todoオブジェクトの終了記号を追加する
        } // 全Todoの処理を終了する
        return json.append("]").toString(); // JSON配列を閉じて文字列として返す
    } // JSON作成メソッドを終了する

    private static String escapeJson(String value) { // 文字列をJSON用にエスケープする
        StringBuilder escaped = new StringBuilder(); // エスケープ後の文字列を組み立てる
        for (int i = 0; i < value.length(); i++) { // 文字列を1文字ずつ処理する
            char c = value.charAt(i); // 現在の文字を取得する
            switch (c) { // エスケープが必要な文字を判定する
                case '"' -> escaped.append("\\\""); // ダブルクォートをエスケープする
                case '\\' -> escaped.append("\\\\"); // バックスラッシュをエスケープする
                case '\b' -> escaped.append("\\b"); // バックスペースをエスケープする
                case '\f' -> escaped.append("\\f"); // フォームフィードをエスケープする
                case '\n' -> escaped.append("\\n"); // 改行をエスケープする
                case '\r' -> escaped.append("\\r"); // 復帰をエスケープする
                case '\t' -> escaped.append("\\t"); // タブをエスケープする
                default -> { // それ以外の文字を処理する
                    if (c < 0x20) { // JSONで直接使えない制御文字か確認する
                        escaped.append(String.format("\\u%04x", (int) c)); // 制御文字をUnicode形式でエスケープする
                    } else { // 通常の文字の場合に処理する
                        escaped.append(c); // 文字をそのまま追加する
                    } // 制御文字の判定を終了する
                } // 通常文字の処理を終了する
            } // 文字の種類による処理を終了する
        } // 全文字の処理を終了する
        return escaped.toString(); // エスケープ済みの文字列を返す
    } // JSONエスケープメソッドを終了する

    private static void sendJson(HttpExchange exchange, int status, String message) throws IOException { // JSONレスポンスを送信する
        byte[] body = message.getBytes(StandardCharsets.UTF_8); // JSON文字列をUTF-8のバイト列へ変換する
        exchange.getResponseHeaders().set("Content-Type", "application/json"); // charsetなしのJSON形式を指定する
        exchange.sendResponseHeaders(status, body.length); // ステータスコードと本文サイズを送信する
        exchange.getResponseBody().write(body); // JSON本文をレスポンスへ書き込む
        exchange.close(); // HTTPレスポンスを終了する
    } // JSON送信メソッドを終了する

    private static void redirect(HttpExchange exchange, String filter, String sort, String direction, String category) throws IOException {
        if (!"active".equals(filter) && !"done".equals(filter))
            filter = "all";
        if (!"name".equals(sort) && !"deadline".equals(sort))
            sort = "added";
        direction = "asc".equals(direction) ? "asc" : "desc";
        exchange.getResponseHeaders().set("Location", "/?filter=" + filter + "&sort=" + sort
                + "&direction=" + direction
                + "&category=" + encodeUrl(category == null ? "" : category));
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String message)
            throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
