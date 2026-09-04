package eu.wohlben.qits.configuration.dto;

/**
 * One configured container-image version: the image, the version stored for it, and the entry that
 * holds it.
 *
 * <p>{@code image} is the unqualified name qits-ci releases under ({@code qits/workspace}), so a
 * consumer composing a tag writes {@code image + ":" + version} and nothing else. {@code
 * application} and {@code key} name the entry the version was read from — the same pair a person can
 * open in the editor — so a row is answerable rather than merely asserted.
 *
 * <p><b>One row per (image &rarr; application+key) mapping</b>, which is why an image can appear
 * twice: {@code qits/workspace} is started by two applications, under a key of each one's own, and
 * collapsing them would lose which entry is behind the version.
 */
public record ImagePinDto(String image, String version, String application, String key) {}
