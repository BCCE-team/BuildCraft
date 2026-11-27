package buildcraft.api.v2.registry;

/** Owner lookup must be side-effect-free and must not register additional content. */
public interface RegistrationContext {
    String owner();
}
