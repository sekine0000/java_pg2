public class Item { // Itemという名前のクラス（プログラムのまとまり）を作ります。
    public static void main(String[] args) { // プログラムを実行したとき、最初に動く部分です。
        String title = "牛乳を買う"; // String型（文字を入れる種類）の変数titleに文字を入れます。
        String html = "<li>" + title + "</li>"; // +で文字をつなぎ、HTMLの1行を作ります。
        System.out.println(html); // 作ったHTMLをターミナル（文字で結果を表示する画面）に出します。
    } // 最初に動く部分の終わりです。
} // Itemクラスの終わりです。
