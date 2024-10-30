public class Planet {
    private final double GRAV_CONST = 6.67e-11;
    public double xxPos;
    public double yyPos;
    public double xxVel;
    public double yyVel;
    public double mass;
    public String imgFileName;

    public Planet( double xP, double yP, double xV, double yV, double m, String img ) {
        xxPos = xP;
        yyPos = yP;
        xxVel = xV;
        yyVel = yV;
        mass = m;
        imgFileName = img;
    }

    public Planet( Planet p ) {
        xxPos = p.xxPos;
        yyPos = p.yyPos;
        xxVel = p.xxVel;
        yyVel = p.yyVel;
        mass = p.mass;
        imgFileName = p.imgFileName;
    }

    public double calcDistance( Planet p ) {
        return Math.sqrt((xxPos - p.xxPos) * (xxPos - p.xxPos) + (yyPos - p.yyPos) * (yyPos - p.yyPos));
    }

    public double calcForceExertedBy( Planet p ) {
        double r = this.calcDistance(p);          //减少一半时间开销
        return GRAV_CONST * mass * p.mass / (r * r);
    }

    public double calcForceExertedByX( Planet p ) {
        double r = this.calcDistance(p);   //减少2/3时间开销
        return GRAV_CONST * this.mass * p.mass * ( p.xxPos - this.xxPos ) / ( r*r*r );
    }

    public double calcForceExertedByY( Planet p ) {
        double r = this.calcDistance(p);
        return GRAV_CONST * this.mass * p.mass * ( p.yyPos - this.yyPos) / ( r*r*r );
    }

    private boolean equal( Planet p ){
        if( xxPos == p.xxPos && yyPos == p.yyPos ) return true;
        else return false;
    } //helper
    public double calcNetForceExertedByX(Planet[] all) {
        double sum = 0;
        for (int i = 0; i < all.length; i++) {
            Planet p = all[i];     //仅仅提升代码可读性，对性能影响微乎其微
            if( !this.equal(p) ) {       //同一星球跳过
                sum += this.calcForceExertedByX(p);
            }
        }
        return sum;
    }

    public double calcNetForceExertedByY(Planet[] all) {
        double sum = 0;
        for (int i = 0; i < all.length; i++) {
            Planet p = all[i];
            if( !this.equal(p) ) {
                sum += this.calcForceExertedByY(p);
            }
        }
        return sum;
    }

    /*  上方法时间开销为n，则该方法时间开销为kn，k为calcForce的时间开销，为常数。
     *  其实两者差不多，这种方法多一次calcDistance，k~2。                  */
//    public double calcNetForceExertedByX( Planet[] all ){
//        double sum = 0;
//        for(int i=0; i<all.length; i++){
//            Planet p = all[i];
//            sum += this.calcNetForceExertedBy(p) * Math.abs( this.xxPos - p.xxPos ) / this.calcDistance(p);
//        }
//        return sum;
//    }

    public void update( double t, double Fx, double Fy ){
         xxVel += t * Fx / mass;
         yyVel += t * Fy / mass;
         xxPos += t * xxVel;
         yyPos += t * yyVel;
    }
    
    public void draw(){
        StdDraw.picture(xxPos, yyPos, "images/"+imgFileName);
    }
}
