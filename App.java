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
import java.text.Collator;
import java.util.ArrayList; // ★ SELECT結果の保持用に追加
import java.util.List; // ★ SELECT結果の保持用に追加
import java.util.Locale;

public class App {
    private static final String DB_URL = "jdbc:sqlite:todos.db"; // ★ CSVではなくSQLiteへ接続
    private static final Path LEGACY_CSV = Path.of("todos.csv");

    private record Todo(int id, String title, boolean done, String deadline, String category) {
    } // ★ DBの1行を表す

    private static Connection connect() throws SQLException { // ★ DB接続処理へ変更
        return DriverManager.getConnection(DB_URL); // ★ todos.dbへ接続
    }

    private static void initializeDatabase() throws SQLException, IOException { // ★ DB初期化を追加
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
            if (isNewDatabase) {
                importLegacyCsv(connection);
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

    private static void addTodo(String title, String deadline, String category) throws SQLException { // ★ CSV保存をINSERTへ変更
        String sql = "INSERT INTO todos (title, done, deadline, category) VALUES (?, ?, ?, ?)"; // ★ 追加SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setString(1, title); // ★ タイトルを設定
            statement.setInt(2, 0); // ★ 未完了を0で保存
            statement.setString(3, deadline); // ★ 締め切り日を設定
            statement.setString(4, category); // カテゴリを設定
            statement.executeUpdate(); // ★ INSERTを実行
        }
    }

    private static void completeTodo(int id) throws SQLException { // ★ リスト更新をUPDATEへ変更
        String sql = "UPDATE todos SET done = CASE WHEN done = 0 THEN 1 ELSE 0 END WHERE id = ?"; // 完了状態を切り替えるSQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, id); // ★ 対象idを設定
            statement.executeUpdate(); // ★ UPDATEを実行
        }
    }

    private static void editTodo(int id, String title) throws SQLException {
        String sql = "UPDATE todos SET title = ? WHERE id = ?";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setInt(2, id);
            statement.executeUpdate();
        }
    }

    private static void deleteTodo(int id) throws SQLException { // ★ リスト削除をDELETEへ変更
        String sql = "DELETE FROM todos WHERE id = ?"; // ★ 削除SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql)) { // ★ PreparedStatementを使用
            statement.setInt(1, id); // ★ 対象idを設定
            statement.executeUpdate(); // ★ DELETEを実行
        }
    }

    private static void deleteCompletedTodos() throws SQLException {
        String sql = "DELETE FROM todos WHERE done = 1";
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static List<Todo> findAllTodos() throws SQLException { // ★ CSV読込をSELECTへ変更
        List<Todo> todos = new ArrayList<>(); // ★ SELECT結果の格納先
        String sql = "SELECT id, title, done, deadline, category FROM todos ORDER BY id"; // ★ 一覧取得SQL
        try (Connection connection = connect(); // ★ DBへ接続
                PreparedStatement statement = connection.prepareStatement(sql); // ★ PreparedStatementを使用
                ResultSet resultSet = statement.executeQuery()) { // ★ SELECTを実行
            while (resultSet.next()) { // ★ 結果を1行ずつ読み出す
                todos.add(new Todo( // ★ DBの行からTodoを作成
                        resultSet.getInt("id"), // ★ idを読み出す
                        resultSet.getString("title"), // ★ titleを読み出す
                        resultSet.getInt("done") != 0, // ★ doneをbooleanへ変換
                        resultSet.getString("deadline"), // ★ 締め切り日を読み出す
                        resultSet.getString("category"))); // カテゴリを読み出す
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
                sendJson(exchange, 200, createTodosJson()); // 全TodoをJSON形式で返す
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

    private static void handle(HttpExchange exchange) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/add") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = formValue(body, "todo").trim();
            String deadline = formValue(body, "deadline").trim();
            String category = normalizedCategory(formValue(body, "category"));
            if (!title.isEmpty()) {
                addTodo(title, deadline, category); // ★ INSERTで追加
            }
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/edit") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Integer id = formId(body);
            String title = formValue(body, "title").trim();
            if (id != null && !title.isEmpty()) {
                editTodo(id, title);
            }
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/done") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                completeTodo(id); // ★ UPDATEで完了
            }
            redirect(exchange, queryValue(exchange, "filter"), queryValue(exchange, "sort"),
                    queryValue(exchange, "category"));
        } else if (path.equals("/delete") && method.equals("GET")) {
            Integer id = queryId(exchange);
            if (id != null) {
                deleteTodo(id); // ★ DELETEで削除
            }
            redirect(exchange, queryValue(exchange, "filter"), queryValue(exchange, "sort"),
                    queryValue(exchange, "category"));
        } else if (path.equals("/delete-completed") && method.equals("POST")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deleteCompletedTodos();
            redirect(exchange, formValue(body, "filter"), formValue(body, "sort"),
                    formValue(body, "categoryFilter"));
        } else if (path.equals("/") && method.equals("GET")) {
            send(exchange, 200, "text/html", createPage(
                    queryValue(exchange, "filter"), queryValue(exchange, "sort"),
                    queryValue(exchange, "category"), queryId(exchange),
                    queryValue(exchange, "q"))); // ★ 各条件に合う一覧を表示
        } else if (path.equals("/search") && method.equals("GET")) {
            send(exchange, 200, "text/html", createPage(queryValue(exchange, "filter"),
                    queryValue(exchange, "sort"), queryValue(exchange, "category"), null,
                    queryValue(exchange, "q")));
        } else {
            send(exchange, 404, "text/plain", "ページが見つかりません");
        }
    }

    private static String createPage(String filter, String sort, String categoryFilter, Integer editId, String keyword) throws SQLException { // ★
                                                                                                       // filterとsortを受け取る
        List<Todo> todos = findAllTodos(); // ★ SELECTで一覧を読み出す
        List<Todo> displayTodos = new ArrayList<>(); // ★ 元データとは別に表示用一覧を作る
        keyword = keyword == null ? "" : keyword;
        categoryFilter = categoryFilter == null ? "" : categoryFilter;
        if (!"active".equals(filter) && !"done".equals(filter)) {
            filter = "all"; // ★ 指定なし・不明な値は全部として扱う
        }
        if (!"name".equals(sort)) {
            sort = "new"; // ★ 指定なし・不明な値は新しい順として扱う
        }
        for (Todo todo : todos) {
            if ((filter.equals("all") || filter.equals("active") && !todo.done()
                    || filter.equals("done") && todo.done()) && todo.title().contains(keyword)
                    && (categoryFilter.isEmpty() || normalizedCategory(todo.category()).equals(categoryFilter))) {
                displayTodos.add(todo); // ★ 条件に一致するTodoだけを表示用一覧へ追加
            }
        }
        if (sort.equals("name")) {
            Collator collator = Collator.getInstance(Locale.JAPANESE);
            displayTodos.sort((a, b) -> {
                int titleOrder = collator.compare(a.title(), b.title());
                return titleOrder != 0 ? titleOrder : Integer.compare(b.id(), a.id());
            });
        } else {
            displayTodos.sort((a, b) -> Integer.compare(b.id(), a.id()));
        }
        StringBuilder html = new StringBuilder(
                """
                        <!doctype html>
                        <html lang="ja">
                        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>わたしのTodo</title>
                        <style>
                        *{box-sizing:border-box}body{max-width:640px;margin:40px auto;padding:0 16px;background:#f6f8fa;color:#24292f;font-family:system-ui,sans-serif;font-size:16px}h1{font-size:24px}form{display:flex;gap:8px}input,select{min-width:0;flex:1;padding:10px 12px;border:1px solid #d0d7de;border-radius:6px;background:#fff;color:#24292f}button,.action{display:inline-block;padding:9px 14px;border:0;border-radius:6px;color:#fff;font-size:16px;font-weight:600;line-height:1.25;text-decoration:none;white-space:nowrap}button{background:#1f883d;cursor:pointer}ul{margin:16px 0 0;padding:0;list-style:none}li{display:flex;align-items:center;gap:8px;margin-bottom:8px;padding:12px 14px;border:1px solid #d0d7de;border-radius:8px;background:#fff}.todo-title,.edit-form{min-width:0;flex:1;overflow-wrap:anywhere}.todo-title.completed{color:#8c959f;text-decoration:line-through}.actions{display:flex;gap:8px}.done{background:#0969da}.edit{background:#8250df}.delete{background:#cf222e}#theme-toggle{margin-bottom:16px;background:#57606a}[data-theme="dark"]{color-scheme:dark}[data-theme="dark"] body{background:#0d1117;color:#e6edf3}[data-theme="dark"] input,[data-theme="dark"] select{border-color:#6e7681;background:#161b22;color:#e6edf3}[data-theme="dark"] li{border-color:#30363d;background:#161b22}[data-theme="dark"] button{background:#238636}[data-theme="dark"] #theme-toggle{background:#6e7681}[data-theme="dark"] .done{background:#1f6feb}[data-theme="dark"] .edit{background:#8957e5}[data-theme="dark"] .delete{background:#da3633}[data-theme="dark"] .todo-title.completed{color:#8b949e}@media(max-width:480px){body{margin:24px auto}li{align-items:stretch;flex-direction:column}.actions{width:100%}.action{flex:1;text-align:center}}
                        </style>
                        <script>if(localStorage.getItem('theme')==='dark'){document.documentElement.dataset.theme='dark'}</script>
                        </head><body><h1>わたしのTodo</h1>
                        <button id="theme-toggle" type="button">ライト／ダーク</button>
                        <form method="post" action="/add"><input name="todo"><input type="date" name="deadline"><input name="category" placeholder="カテゴリ（未入力は未分類）"><button>追加</button>
                        """);
        html.append("<input type='hidden' name='filter' value='").append(filter).append("'>")
                .append("<input type='hidden' name='sort' value='").append(sort).append("'>")
                .append("<input type='hidden' name='categoryFilter' value='")
                .append(escapeHtml(categoryFilter)).append("'></form>")
                .append("<form method='get' action='/search'>")
                .append("<input name='q' placeholder='キーワードで検索' value='")
                .append(escapeHtml(keyword)).append("'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='category' value='").append(escapeHtml(categoryFilter))
                .append("'><button>検索</button></form>")
                .append("<nav><a href='/?filter=all&sort=").append(sort).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>全部</a> | ")
                .append("<a href='/?filter=active&sort=").append(sort).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>未完了だけ</a> | ")
                .append("<a href='/?filter=done&sort=").append(sort).append("&category=")
                .append(encodeUrl(categoryFilter)).append("'>完了だけ</a></nav>")
                .append("<form method='get' action='/'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='category' value='")
                .append(escapeHtml(categoryFilter)).append("'><label>並び順 <select name='sort' onchange='this.form.submit()'>")
                .append("<option value='new'").append(sort.equals("new") ? " selected" : "")
                .append(">新しい順</option><option value='name'")
                .append(sort.equals("name") ? " selected" : "")
                .append(">名前順</option></select></label></form>")
                .append("<form method='get' action='/'><input type='hidden' name='filter' value='")
                .append(filter).append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><label>カテゴリ <select name='category' onchange='this.form.submit()'>")
                .append("<option value=''>すべて</option>");
        String selectedCategory = categoryFilter;
        todos.stream().map(todo -> normalizedCategory(todo.category())).distinct()
                .sorted(Collator.getInstance(Locale.JAPANESE))
                .forEach(category -> html.append("<option value='").append(escapeHtml(category)).append("'")
                        .append(category.equals(selectedCategory) ? " selected" : "").append(">")
                        .append(escapeHtml(category)).append("</option>"));
        html.append("</select></label></form>");
        long completedCount = todos.stream().filter(Todo::done).count();
        html.append("<form method='post' action='/delete-completed'")
                .append(completedCount > 0
                        ? " onsubmit=\"return confirm('完了済みのTodoをすべて削除しますか？')\""
                        : "")
                .append("><input type='hidden' name='filter' value='").append(filter)
                .append("'><input type='hidden' name='sort' value='").append(sort)
                .append("'><input type='hidden' name='categoryFilter' value='")
                .append(escapeHtml(categoryFilter))
                .append("'><button class='delete' type='submit'>完了済みを一括削除</button></form>");
        if (displayTodos.isEmpty()) {
            html.append(keyword.isEmpty()
                    ? "<p>やることは、いまゼロです</p>"
                    : "<p>検索結果は0件です。</p>");
        } else {
            html.append("<p>").append(todos.size()).append("件中")
                    .append(completedCount).append("件 完了</p>");
            html.append("<ul>");
            for (Todo todo : displayTodos) {
                html.append("<li>");
                if (editId != null && editId == todo.id()) {
                    html.append("<form class='edit-form' method='post' action='/edit'>")
                            .append("<input name='title' value='").append(escapeHtml(todo.title()))
                            .append("' required><input type='hidden' name='id' value='").append(todo.id())
                            .append("'><input type='hidden' name='filter' value='").append(filter)
                            .append("'><input type='hidden' name='sort' value='").append(sort)
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
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>編集</a>")
                        .append("<a class='action done' href='/done?id=").append(todo.id())
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>完了</a>")
                        .append("<a class='action delete' href='/delete?id=").append(todo.id())
                        .append("&filter=").append(filter).append("&sort=").append(sort).append("&category=")
                        .append(encodeUrl(categoryFilter)).append("'>削除</a></span></li>");
            }
            html.append("</ul>");
        }
        return html.append("<script>document.getElementById('theme-toggle').addEventListener('click',function(){const dark=document.documentElement.dataset.theme!=='dark';document.documentElement.dataset.theme=dark?'dark':'light';localStorage.setItem('theme',dark?'dark':'light');this.setAttribute('aria-pressed',dark);});</script></body></html>").toString();
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

    private static String createTodosJson() throws SQLException { // 全TodoのJSON文字列を作成する
        List<Todo> todos = findAllTodos(); // データベースから全Todoを取得する
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

    private static void redirect(HttpExchange exchange, String filter, String sort, String category) throws IOException {
        if (!"active".equals(filter) && !"done".equals(filter))
            filter = "all";
        if (!"name".equals(sort))
            sort = "new";
        exchange.getResponseHeaders().set("Location", "/?filter=" + filter + "&sort=" + sort
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
