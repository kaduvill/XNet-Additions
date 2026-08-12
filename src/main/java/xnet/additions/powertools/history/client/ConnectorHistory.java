package xnet.additions.powertools.history.client;

import mcjty.xnet.api.keys.SidedPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConnectorHistory {
    public static final int LIMIT = 5;
    private final List<Entry> previous = new ArrayList<>(LIMIT);
    private Entry current;
    private int revision;

    public void visit(SidedPos connector, int channel) {
        if (connector == null || channel < 0) {return;}
        if (current != null && channel == current.channel && connector.equals(current.connector)) {return;}
        Entry next = new Entry(connector, channel);
        if (current != null) {
            previous.remove(next);
            previous.add(0, current);
            if (previous.size() > LIMIT) {previous.remove(previous.size() - 1);}
        }
        current = next;
        revision++;
    }

    public void remove(Entry entry) {
        if (previous.remove(entry)) {revision++;}
    }

    public List<Entry> getPrevious() {return Collections.unmodifiableList(previous);}
    public int getRevision() {return revision;}

    public static final class Entry {
        private final SidedPos connector;
        private final int channel;

        private Entry(SidedPos connector, int channel) {
            this.connector = connector;
            this.channel = channel;
        }

        public SidedPos getConnector() {return connector;}
        public int getChannel() {return channel;}

        @Override
        public boolean equals(Object object) {
            if (this == object) {return true;}
            if (!(object instanceof Entry)) {return false;}
            Entry entry = (Entry) object;
            return channel == entry.channel && connector.equals(entry.connector);
        }

        @Override
        public int hashCode() {return 31 * connector.hashCode() + channel;}
    }
}