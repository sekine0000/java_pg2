import java.util.ArrayList; // ArrayList（データを順番に入れておく箱）を使えるようにします。
import java.util.List; // List（複数のデータを順番に扱う仕組み）を使えるようにします。

class Todo { // Todo（やること）を表すクラス（データと処理の設計図）です。
    String title; // title（やることの名前）を保存します。
    boolean done; // done（済んだかどうか）をtrueまたはfalseで保存します。

    Todo(String title, boolean done) { // Todoを作るときに、名前と済んだかどうかを受け取ります。
        this.title = title; // 受け取った名前を、このTodoのtitleに保存します。
        this.done = done; // 受け取った状態を、このTodoのdoneに保存します。
    } // Todoを作る処理はここまでです。

    String toItem() { // TodoをHTMLのli形式（一覧の1項目を表す書き方）に変えるメソッド（処理）です。
        if (done) { // doneがtrue、つまり済んでいるかを確認します。
            return "<li>[済] " + title + "</li>"; // 済んでいる場合の文字列を返します。
        } // 済んでいる場合の処理はここまでです。
        return "<li>" + title + "</li>"; // 済んでいない場合の文字列を返します。
    } // toItemメソッドはここまでです。
} // Todoクラスはここまでです。

public class Main { // Mainクラス（プログラム全体の中心となる設計図）です。
    public static void main(String[] args) { // プログラムを実行したときに最初に呼ばれる部分です。
        List<Todo> todos = new ArrayList<>(); // Todoを複数入れるListを作ります。
        todos.add(new Todo("牛乳を買う", false)); // 未完了の「牛乳を買う」をListに追加します。
        todos.add(new Todo("ゴミを出す", true)); // 完了済みの「ゴミを出す」をListに追加します。

        for (Todo todo : todos) { // for（繰り返し）でListからTodoを1件ずつ取り出します。
            System.out.println(todo.toItem()); // toItemの結果をターミナル（文字を表示する画面）に1行出力します。
        } // 繰り返しはここまでです。
    } // mainメソッドはここまでです。
} // Mainクラスはここまでです。
