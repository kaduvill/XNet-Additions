package xnet.additions.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

public final class ConnectorSpeedHelper {

    public static final IntList NORMAL_SPEEDS = new IntArrayList(new int[]{2, 6, 10, 20});
    public static final IntList ADVANCED_SPEEDS = new IntArrayList(new int[]{1, 2, 6, 10, 20});

    private ConnectorSpeedHelper() {
    }

    public static IntList getSpeeds(boolean advanced) {
        return advanced ? ADVANCED_SPEEDS : NORMAL_SPEEDS;
    }

    public static String[] getSpeedChoices(boolean advanced) {
        IntList speeds = getSpeeds(advanced);
        String[] result = new String[speeds.size()];
        for (int i = 0; i < speeds.size(); i++) {
            result[i] = Integer.toString(speeds.getInt(i) * 10);
        }
        return result;
    }

    public static int getMinSpeed(boolean advanced) {
        return getSpeeds(advanced).getInt(0);
    }

    public static boolean isValidSpeed(int speed, boolean advanced) {
        return getSpeeds(advanced).contains(speed);
    }

    public static int sanitizeSpeed(int speed, boolean advanced) {
        int min = getMinSpeed(advanced);

        if (speed <= 0) {
            return min;
        }

        if (!isValidSpeed(speed, advanced)) {
            return min;
        }

        return speed;
    }
}