public class Test1{
    public static void main(String[] args) {
        int a=2; // expected - []
        int b=3; // expected - []
        int x=a+b; // expected - [a+b a*b]
        int y=a*b; // expected - [a+b a*b]

        while(y>a+b){ // expected - [a+b]
            a=a+1; // expected - [a+1]
            x=a+b; // expected - [a+b]
        }

    }
}