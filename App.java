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
import java.util.List; // ★ SELECT結果の保持用に追加
import java.util.Locale;

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db"; // ★ CSVではなくSQLiteへ接続
    private static final Path LEGACY_CSV = Path.of("todos.csv");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, User> SESSIONS = new ConcurrentHashMap<>();

    private record Todo(int id, String title, boolean done, String deadline, String category) {
    } // ★ DBの1行を表す

    private record User(int id, String username) {
    }

    private static Connection connect() throws SQLException { // ★ DB接続処理へ変更
        return DriverManager.getConnection(DB_URL); // ★ todos.dbへ接続
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
                                "INSERT INTO users (username, password_hash) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                            createUser.setString(1, "わたし");
                            createUser.setString(2, hashPassword("0000"));
                            createUser.executeUpdate();
                            try (ResultSet keys = createUser.getGeneratedKeys()) {
                                keys.next();
                                defaultUserId = keys.getInt(1);
                            }
                        }
                    }
                }
            }
            try (PreparedStatement migrate = connection.prepareStatement("UPDATE todos SET user_id = ? WHERE user_id IS NULL")) {
                migrate.setInt(1, defaultUserId);
                migrate.executeUpdate();
            }
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

        if (path.equals("/add") && method.equals("POST")) {
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
            send(exchange, 200, "text/html", createDeleteConfirmationPage(user));
        } else if (path.equals("/settings/delete") && method.equals("POST")) {
            deleteAccount(user.id());
            endAllSessions(exchange, user.id());
            redirectTo(exchange, "/login");
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
        html.append(escapeHtml(user.username())).append("のTodo</h1><span><a class='action edit' href='/settings'>設定</a> ")
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
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, hashPassword(password));
            try {
                statement.executeUpdate();
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                    return null;
                }
                throw e;
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getInt(1), username);
            }
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
        redirectTo(exchange, "/");
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
                + "<header><h1>アカウント設定</h1><a href='/'>Todoへ戻る</a></header>"
                + (error.isEmpty() ? "" : "<p class='notice error'>" + escapeHtml(error) + "</p>")
                + (message.isEmpty() ? "" : "<p class='notice success'>" + escapeHtml(message) + "</p>")
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
                + "<style>*{box-sizing:border-box}html{background:#f6f8fa}body{max-width:680px;margin:24px auto;padding:0 20px 32px;font-family:system-ui,sans-serif;color:#24292f;line-height:1.5}main>header{display:flex;align-items:center;justify-content:space-between;gap:16px}h1{font-size:26px}h2{margin-top:0;font-size:18px}section{margin:12px 0;padding:18px;border:1px solid #d0d7de;border-radius:10px;background:#fff}label{display:block;margin:12px 0 5px}input,select{width:100%;height:42px;padding:9px 11px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#24292f;font:inherit}button,.button{display:inline-flex;min-height:42px;align-items:center;justify-content:center;margin-top:14px;padding:9px 14px;border:0;border-radius:6px;background:#1f883d;color:#fff;font:inherit;font-weight:600;text-decoration:none;cursor:pointer}.danger-zone{border-color:#cf222e}.danger{background:#cf222e}.secondary{border:1px solid #afb8c1;background:#fff;color:#24292f}.notice{padding:10px;border-radius:6px}.error{background:#ffebe9;color:#cf222e}.success{background:#dafbe1;color:#116329}.theme-status{min-height:24px;margin:8px 0 0;color:#57606a}.confirmation-actions{display:flex;gap:10px}.confirmation-actions form{margin:0}.confirmation-actions .button,.confirmation-actions button{margin-top:8px}[data-theme='dark']{color-scheme:dark;background:#0d1117}[data-theme='dark'] body{color:#e6edf3}[data-theme='dark'] section{border-color:#30363d;background:#161b22}[data-theme='dark'] input,[data-theme='dark'] select{border-color:#6e7681;background:#0d1117;color:#e6edf3}[data-theme='dark'] a{color:#58a6ff}[data-theme='dark'] .secondary{border-color:#6e7681;background:#21262d;color:#e6edf3}[data-theme='dark'] .danger-zone{border-color:#f85149}[data-theme='dark'] .error{background:#3d1515;color:#ff7b72}[data-theme='dark'] .success{background:#12261e;color:#56d364}[data-theme='dark'] .theme-status{color:#8b949e}@media(max-width:480px){main>header{align-items:flex-start;flex-direction:column}.confirmation-actions{align-items:stretch;flex-direction:column}.confirmation-actions form,.confirmation-actions button,.confirmation-actions .button{width:100%}}</style>"
                + "</head><body><main>";
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

    private static Integer formId(String body) {
        try {
            return Integer.parseInt(formValue(body, "id"));
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
