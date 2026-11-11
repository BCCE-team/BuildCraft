package buildcraft.lib.update;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Minimal release metadata: BCCE version and the targets actually published. */
public final class ReleaseManifest {
    public final String version;
    public final Set<String> targets;
    public ReleaseManifest(String version, Set<String> targets) {
        this.version = BCVersion.parse(version).display();
        if (targets == null || targets.isEmpty()) throw new IllegalArgumentException("Manifest has no targets");
        this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        for (String target : this.targets) {
            if (target == null || !target.matches("[0-9.]+-(forge|neoforge|fabric)"))
                throw new IllegalArgumentException("Invalid target: " + target);
        }
    }
    public boolean supports(String target) { return targets.contains(target); }
}
