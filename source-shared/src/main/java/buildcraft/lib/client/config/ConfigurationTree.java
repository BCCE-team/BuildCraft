package buildcraft.lib.client.config;

import java.util.*;

/** A branch is navigation only. Direct values on a branch get a distinct leaf. */
final class ConfigurationTree {
    record Node(List<String> path, boolean branch, boolean general) {
        int depth() { return path.size() - 1 + (general ? 1 : 0); }
    }
    private static final Comparator<List<String>> ORDER = (a,b) -> {
        for (int i=0; i<Math.min(a.size(), b.size()); i++) {
            int c=a.get(i).compareTo(b.get(i)); if(c!=0) return c;
        }
        return Integer.compare(a.size(),b.size());
    };
    static List<Node> visible(Collection<List<String>> categories, Set<List<String>> expanded) {
        Set<List<String>> direct = new HashSet<>(categories);
        Set<List<String>> paths = new TreeSet<>(ORDER);
        for (var path : direct) for(int i=1;i<=path.size();i++) paths.add(List.copyOf(path.subList(0,i)));
        List<Node> nodes = new ArrayList<>();
        for (var path : paths) {
            boolean shown = true;
            for (int i=1;i<path.size();i++) if(!expanded.contains(path.subList(0,i))) { shown=false; break; }
            if(!shown) continue;
            boolean branch = paths.stream().anyMatch(p -> p.size()==path.size()+1 && under(p,path));
            nodes.add(new Node(path,branch,false));
            if(branch && expanded.contains(path) && direct.contains(path)) nodes.add(new Node(path,false,true));
        }
        return List.copyOf(nodes);
    }
    static boolean under(List<String> path,List<String> parent) {
        return path.size()>=parent.size() && path.subList(0,parent.size()).equals(parent);
    }
}
