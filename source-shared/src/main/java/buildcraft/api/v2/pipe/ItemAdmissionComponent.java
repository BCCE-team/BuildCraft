package buildcraft.api.v2.pipe;

/**
 * Admission policy for both neighbouring pipes and external item injection.
 * The predicate must be read-only and return the same decision for SIMULATE and EXECUTE.
 * Returning false rejects the entire offer without removing it from its current owner.
 * Failed callbacks are treated as rejection.
 */
public interface ItemAdmissionComponent extends PipeComponent {
    boolean accepts(ItemAdmissionContext context);
}
