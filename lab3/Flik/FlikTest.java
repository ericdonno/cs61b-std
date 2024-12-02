import static org.junit.Assert.*;

import org.junit.Test;

public class FlikTest {
    @Test
    public void testOne(){
        int a = 1, a_ = 1;
        assertEquals(true, Flik.isSameNumber(a,a_));
    }

    @Test
    public void testZero(){
        int a = 0, a_ = 0;
        assertEquals(true, Flik.isSameNumber(a,a_));
    }

    @Test
    public void testOneByte(){
        int a = 128;
        int a_ = 128;
        assertEquals(true, Flik.isSameNumber(a,a_));
    }

//    @Test
//    public void testOneByte(){
//        int n =1;
//        int a = (int) (1L << (n * 8 - 1)) - 1;
//        int a_ = (int) (1L << (n * 8 - 1)) - 1;
//        assertEquals(true, Flik.isSameNumber(a,a_));
//    }

    public static void main(String[] args) {
        int step = 1;
        for(int i = 0, j = 0; i < 256; i+=step, j+=step ){
            System.out.println(Flik.isSameNumber(i,j)+" "+i);
        }
    }
}
