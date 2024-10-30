public class NBody {
    private static String backgroundImg = "images/starfield.jpg";

    public static void main(String[] args){
        //Collecting All Needed Input
        double T = Double.parseDouble(args[0]);
        double dt = Double.parseDouble(args[1]);
        String filename = args[2];
        double universeRadius = readRadius(filename);
        Planet[] planets = readPlanets(filename);
        
        //Drawing the Background
        StdDraw.setScale(-universeRadius, universeRadius);
        drawBackground(backgroundImg);
        //Drawing All of the Planets
        drawPlanets(planets);
//        StdDraw.show();  //"There is no reason to call this method unless double buffering is enabled."
//        StdDraw.pause(2000);

        //test
//        StdDraw.pause(1000);
//        StdDraw.enableDoubleBuffering();
//        drawBackground(backgroundImg);
//        planets[3].draw();
//        StdDraw.show();

        //Creating an Animation
        StdDraw.enableDoubleBuffering();
        double time = 0;
        int N = planets.length;
        while( time <= T ){
            double[] xForces = new double[N];
            double[] yForces = new double[N];
            for(int i=0; i<N; i++){
                xForces[i] = planets[i].calcNetForceExertedByX(planets);
                yForces[i] = planets[i].calcNetForceExertedByY(planets);
            }
//            System.out.println("sun by" + xForces[3]+ " " + yForces[3] );
            for(int i=0; i<N; i++){
                System.out.println("Planet"+ i+  "Position: (" + planets[i].xxPos + ", " + planets[i].yyPos + ")");
                planets[i].update( dt, xForces[i], yForces[i] );
            }
            drawBackground(backgroundImg);
            drawPlanets(planets);
            StdDraw.show();
            StdDraw.pause(10);
            time += dt;
            System.out.println(time+"/"+T);
        }

        //Printing the Universe
        StdOut.printf("%d\n", planets.length);
        StdOut.printf("%.2e\n", universeRadius);
        for (int i = 0; i < planets.length; i++) {
            StdOut.printf("%11.4e %11.4e %11.4e %11.4e %11.4e %12s\n",
                    planets[i].xxPos, planets[i].yyPos, planets[i].xxVel,
                    planets[i].yyVel, planets[i].mass, planets[i].imgFileName);
        }
    }
    
    public static double readRadius(String img){
        In in = new In(img);
        in.readInt();
        return in.readDouble();
    }

    public  static Planet[] readPlanets(String img){
        In in = new In(img);
        int N = in.readInt();
        in.readDouble();
        Planet[] planets = new Planet[N];
        for(int i=0; i<planets.length; i++){
            planets[i] = new Planet( in.readDouble(), in.readDouble(),
                                     in.readDouble(), in.readDouble(),
                                     in.readDouble(), in.readString());
        }
        return planets;
    }

    private static void drawBackground(String backImg){     //draw background by radius
//        StdDraw.clear();
        StdDraw.picture(0, 0, backImg);
//        StdDraw.pause(2000);
    }
    
    private static void drawPlanets(Planet[] planets){
        for( Planet p : planets ){
            p.draw();
        }
        System.out.println("all drawn");
    }

}