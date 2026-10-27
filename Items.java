// Itemsという名前のクラス（プログラムをまとめる入れ物）を作ります。
public class Items {
    // Javaプログラムが最初に実行するmainメソッド（処理の開始場所）です。
    public static void main(String[] args) {
        // 3件のTodoをtodosという配列（複数の値を順番に入れる箱）に入れます。
        String[] todos = {"牛乳を買う", "卵を買う", "パンを買う"};

        // iを0から1ずつ増やし、todosの件数より小さい間、処理を繰り返します。
        for (int i = 0; i < todos.length; i++) {
            // i番目のTodoを<li>と</li>で囲み、ターミナルへ1行で出力します。
            System.out.println("<li>" + todos[i] + "</li>");
        // for文（繰り返し処理）をここで終わります。
        }
    // mainメソッドをここで終わります。
    }
// Itemsクラスをここで終わります。
}
