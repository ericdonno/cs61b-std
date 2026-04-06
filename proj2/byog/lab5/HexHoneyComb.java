package byog.lab5;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;

import byog.Helper.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 蜂巢
 * */
public class HexHoneyComb implements Iterable<Hexagon>{     //是一个连通分量
    int size;       //一个蜂巢的所有六边形边长都一样
    private int HORI_MEGA_LEN;   //horizontal mega length
    private int VERT_MEGA_LEN;
    private HexList pioneerList;    //源表,一切的开始,用于构造本体和存储其绝对位置
    private int pioInDeque;   //记录源表在allList中的位置，从而可以获得所有List在allList中的位置，都是O(1)
    ArrayDeque<HexList> allList;
    ArrayList<Double> rightOtherHight;  //其他表与首表的纵向相对位置，值为该表头。元素值都为0.5的倍数
    ArrayList<Double> leftOtherHight;
    private int absX;   //绝对位置，原点的绝对坐标
    private int absY;

    /**
     * Constructor by a pioneer hexagon
     */
    public HexHoneyComb(Hexagon pioneerHex, int size) {
        this.size = size;
        HORI_MEGA_LEN = 2 * size - 1;
        VERT_MEGA_LEN = 2 * size;
        pioneerList = new HexList(0,0);
        pioInDeque = 0;
        allList = new ArrayDeque<>();
        rightOtherHight = new ArrayList<>();
        leftOtherHight = new ArrayList<>();

        pioneerList.add(pioneerHex);
        allList.addFirst(pioneerList);
        rightOtherHight.add((double) 0);
        leftOtherHight.add((double) 0);
        absX = pioneerHex.getPosition().x;
        absY = pioneerHex.getPosition().y;
    }

    /**
     * Add a hex (no absolute position) to the left, with relative position
     */
    public void addRight(double hight, TETile style) {
        HexList rightList = allList.getLast();
        double UpperBound = rightOtherHight.get(rightList.horiDis) + 0.5;   //UpperBound，数值和方向意义上的上界
        double LowerBound = UpperBound + rightList.size();
        if (hight > UpperBound && hight < LowerBound) {
            System.out.println("no addRight");   //for test
        } else {
            HexList newRight = new HexList(rightList.horiDis + 1, hight);
            allList.addLast(newRight);;
            rightOtherHight.add(hight);

            Hexagon hex = new Hexagon(size, style);
            hex.setPosition(new Position( absX + newRight.horiDis * HORI_MEGA_LEN, (int) (absY + hight * VERT_MEGA_LEN) ));
            newRight.add(hex);
        }
    }

    public void addLeft(double hight, TETile style) {
        HexList leftList = allList.getFirst();
        double UpperBound = leftOtherHight.get(leftList.horiDis) + 0.5;   //UpperBound，数值和方向意义上的上界
        double LowerBound = UpperBound + leftList.size();
        if (hight > UpperBound && hight < LowerBound) {
            System.out.println("no addLeft");   //for test
        } else {
            //创建新list
            HexList newLeft = new HexList(leftList.horiDis + 1, hight);
            allList.addFirst(newLeft);;
            leftOtherHight.add(hight);

            //add新hex
            Hexagon hex = new Hexagon(size, style);
            hex.setPosition(new Position( absX - newLeft.horiDis * HORI_MEGA_LEN, (int) (absY + hight * VERT_MEGA_LEN) ));
            newLeft.add(hex);


            //addList会改变源表在allList中的位置，addRight不会
            pioInDeque++;
        }
    }

    //暂不支持删除

    /**
     * vert_N is 1 now
     * */
    public void addVertical(int hori_X, int vert_N, int upOdown, TETile style) {
        HexList l = allList.get(pioInDeque + hori_X);
        for (int i = 1; i < vert_N; i++) {
            l = l.belowList;
        }
        if (upOdown != 1 && upOdown != -1) {
            System.out.println("no addVert");
            return;
        }
        double H = upOdown == 1
                ? l.vertHight + 1
                : l.vertHight - l.size();

        Hexagon hex = new Hexagon(size, style);
        hex.setPosition(new Position( absX + hori_X * HORI_MEGA_LEN, (int) (absY + H * VERT_MEGA_LEN)));
        l.add(hex);
    }

    //
    public void addSelf(TETile[][] world) {
        for(Hexagon h:this) {
            h.addSelf(world);
        }
    }

    @Override
    public Iterator<Hexagon> iterator() {
        return new HIterator();
    }


    public class HIterator implements Iterator<Hexagon>{
        private Iterator<HexList> listIterator;  // 当前正在迭代的 HexList
        private Iterator<Hexagon> hexIterator;   // 当前正在迭代的 HexList 中的 Hexagon

        public HIterator() {
            listIterator = allList.iterator();   // 初始化时指向 allList 中的第一个 HexList
            System.out.println("iter cons");
            if (listIterator.hasNext()) {
                hexIterator = listIterator.next().hexs.iterator();  // 初始化指向第一个 HexList 中的 Hexagon
            }
        }

        @Override
        public boolean hasNext() {
            while ( (hexIterator == null || !hexIterator.hasNext() && listIterator.hasNext()) ) {   //如果当前list遍历完了且还有下一个list，就检查下一个
                hexIterator = listIterator.next().hexs.iterator();
            }
            return (hexIterator != null) && hexIterator.hasNext();
        }


        @Override
        public Hexagon next() {
            if (!hasNext()) {    //在检查时就已经完成了kon'm'h
                throw new NoSuchElementException();
            }
            return hexIterator.next();
        }
    }
}


/**
 * Basic storage unit
 * */
class HexList {   //基本存储结构
    ArrayList<Hexagon> hexs;
    int horiDis; //该表与源表的横向相对位置
    double vertHight; //该表头与原点的纵向相对位置
    HexList belowList;


    public HexList(int hd, double vh) {
        hexs = new ArrayList<>();
        horiDis = hd;
        vertHight = vh;
    }

    public void add(Hexagon hex) {
        hexs.add(hex);
    }

    public int size() {
        return hexs.size();
    }

    public Hexagon getFirst() {
        return hexs.get(0);
    }

    public void setDis(int d) {
        this.horiDis = d;
    }

    public void setHight(int h ) {
        this.vertHight = h;
    }

    public void setBelowList(HexList bh) {
        this.belowList = bh;
    }
}