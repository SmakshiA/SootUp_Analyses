public class Test1{
    public int a;
    public int b;
    public void r(){

    }
    public void s(){
        System.out.println(a*b); // expected - [a b]
        r(); // expected - []
    }
    public void t(){
        a=5; // expected - []
        r(); // expected - []
    }
}