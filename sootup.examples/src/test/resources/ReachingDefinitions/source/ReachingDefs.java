public class ReachingDefs {
    public ReachingDefs(){

    }
    public static void main(String[] args){
        int a=4;
        int b=2;
        int c=3;
        int m = c*2;
        if(a<=m){
            a = a+1;
        }
        else if(a<12){
            int t1= a+b;
            a = t1+c;
        }
        System.out.println(a);
    }
}

    /*public static void main(String[] args) {
        int x = 5;  // Definition of x
        int y;
        if (x > 3) {
            y = 10;  // Definition of y
        } else {
            y = 20;  // Another definition of y
        }
        int z = x + y;  // Definition of z; Uses x and y
    }
}*/


/*public class ReachingDefs {
    public static void main(String[] args) {
        int a = 4;
        int b = 2;
        int c = 3;
        int d = c * 2;
        if (a <= d) {
            a++;
        } else if (a < 12) {
            int e = a + b;
            a = e + c;
        }
        System.out.println("Value of a: " + a);
    }
}*/

/*public class ReachingDefs{
    public static int function(int m, int n,int k){
        int a=0;
        for(int i=m-1;i<k;i++){
            if(i>=n){
                a=n;
            }
            a =a+i;
        }
        return a;
    }
}*/


