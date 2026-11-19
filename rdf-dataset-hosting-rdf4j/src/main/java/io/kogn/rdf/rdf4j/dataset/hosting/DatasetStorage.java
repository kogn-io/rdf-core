// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.rdf4j.dataset.hosting;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.hosting.DatasetId;
import lombok.extern.slf4j.Slf4j;

/**
 * The on-disk side of a persistent {@link DatasetLifecycleRdf4j}: where a dataset's directory
 * lies under the storage root, which directories count as datasets, and the deletion-leftover
 * protocol around a delete that can fail part-way.
 *
 * <p>Package-private so that a test can replace the directory walk of {@link #deleteDirectory}
 * and hand the replacement to {@link DatasetLifecycleRdf4j} through its package-private
 * constructor.</p>
 *
 * <h2>Layout</h2>
 *
 * <p>The opaque {@link DatasetId} value is never used as a path. It is Base64url-encoded into a
 * single directory segment, so values such as {@code "../etc"} cannot escape the storage root.
 * The storage root may hold directories this class did not create ({@code data}, {@code logs}).
 * A directory name counts as ours only if it decodes as canonical Base64url, as strictly valid
 * UTF-8 (malformed input is rejected, not replaced), to a value {@link DatasetId} accepts, and
 * encodes back to exactly that name. This is a plausibility test, not proof of origin: no marker
 * file identifies a directory as a dataset, so a foreign directory whose name happens to be a
 * validly encoded id (such as {@code Zm9v}, the encoding of {@code foo}) is taken for that
 * dataset.</p>
 *
 * <h2>Deletion leftover</h2>
 *
 * <p>Removing a directory is a walk that can fail in the middle and leave a <em>deletion
 * leftover</em>. {@link #deleteOrMarkDeletionLeftover(DatasetId)} writes a
 * {@value #DELETION_MARKER_FILE_NAME} file into the directory before the walk starts; the walk
 * removes it again with the rest of the directory, so a marker that is still there means the
 * walk did not finish. The marker lives on disk because it must survive a process restart and a
 * second instance over the same root. Writing it is best-effort; if that fails too, an in-process
 * set is the fallback for the rest of this process's life.</p>
 */
@Slf4j
class DatasetStorage {

  /**
   * Name of the marker file written inside a dataset's directory while its deletion is unfinished.
   * Part of the stored format: a directory carrying it is a deletion leftover, also for an
   * instance of an earlier version over the same root, so the name must not change.
   */
  static final String DELETION_MARKER_FILE_NAME = ".deleting";

  private final Path root;

  /**
   * In-process fallback for ids whose directory could not be marked on disk; see the class
   * documentation.
   */
  private final Set<DatasetId> unmarkedDeletionLeftovers = ConcurrentHashMap.newKeySet();

  /**
   * Creates the storage over {@code root}.
   *
   * @param root root directory under which the datasets live; must not be {@code null}
   * @throws NullPointerException if {@code root} is {@code null}
   */
  DatasetStorage(final Path root) {
    this.root = Objects.requireNonNull(root, "storageRoot is required for PERSISTENT").normalize();
  }

  /**
   * Maps an opaque dataset id to a single, traversal-safe directory under the storage root.
   *
   * @param id the dataset id
   * @return the dataset's directory, which need not exist
   */
  File directoryOf(final DatasetId id) {
    final String segment = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
    final Path dir = root.resolve(segment).normalize();
    if (!dir.startsWith(root)) {
      // unreachable for a Base64url segment; defends the invariant if encoding changes
      throw new IllegalArgumentException("datasetId maps outside storage root: " + id.value());
    }
    return dir.toFile();
  }

  /**
   * Whether a dataset's directory exists, whatever it holds.
   *
   * @param id the dataset id
   * @return whether the directory exists
   */
  boolean hasDirectory(final DatasetId id) {
    return Files.exists(directoryOf(id).toPath());
  }

  /**
   * Whether {@code id} holds no dataset yet: absent, or readable and empty. A directory that
   * cannot be read is neither — it may well hold an intact dataset — so this fails instead of
   * answering "new", which would let a failed {@code init} roll back (delete) the dataset.
   *
   * @param id the dataset id
   * @return whether the directory is absent or empty
   * @throws UncheckedIOException if the directory cannot be read
   */
  boolean isNew(final DatasetId id) {
    final Path path = directoryOf(id).toPath();
    if (Files.notExists(path)) {
      return true;
    }
    try (Stream<Path> entries = Files.list(path)) {
      return entries.findAny().isEmpty();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot tell whether the dataset storage " + path + " is new", e);
    }
  }

  /**
   * The ids of every directory under the storage root that this class itself would have produced
   * for the id it decodes to; a foreign directory such as {@code data} or {@code logs} is skipped.
   * Deletion leftovers are included.
   *
   * @return the ids found; never {@code null}
   * @throws UncheckedIOException if the storage root cannot be listed
   */
  Set<DatasetId> listDatasets() {
    final Set<DatasetId> result = new HashSet<>();
    if (Files.isDirectory(root)) {
      try (Stream<Path> entries = Files.list(root)) {
        entries.filter(Files::isDirectory)
            .map(p -> decodeSegment(p.getFileName().toString()))
            .filter(Objects::nonNull)
            .forEach(result::add);
      } catch (final IOException e) {
        throw new UncheckedIOException("failed to list datasets under " + root, e);
      }
    }
    return result;
  }

  /**
   * The ids whose directory carries the {@value #DELETION_MARKER_FILE_NAME} marker, plus the
   * in-process fallback for a marker that could not be written.
   *
   * @return the ids carrying a deletion leftover; never {@code null}
   * @throws UncheckedIOException if the storage root cannot be listed or a marker's presence
   *     cannot be determined
   */
  Set<DatasetId> listDeletionLeftovers() {
    final Set<DatasetId> result = new HashSet<>(unmarkedDeletionLeftovers);
    if (Files.isDirectory(root)) {
      try (Stream<Path> entries = Files.list(root)) {
        entries.filter(Files::isDirectory)
            .filter(DatasetStorage::carriesDeletionMarker)
            .map(p -> decodeSegment(p.getFileName().toString()))
            .filter(Objects::nonNull)
            .forEach(result::add);
      } catch (final IOException e) {
        throw new UncheckedIOException("failed to list deletion leftovers under " + root, e);
      }
    }
    return result;
  }

  /**
   * Whether {@code id} carries a deletion leftover: the on-disk marker is authoritative, the
   * in-process set is consulted too as the fallback for when writing the marker failed.
   *
   * @param id the dataset id
   * @return whether the id carries a deletion leftover
   * @throws UncheckedIOException if the marker's presence cannot be determined
   */
  boolean hasDeletionLeftover(final DatasetId id) {
    if (unmarkedDeletionLeftovers.contains(id)) {
      return true;
    }
    return carriesDeletionMarker(directoryOf(id).toPath());
  }

  /**
   * Deletes a dataset's directory, marking the id as a deletion leftover on any failure,
   * regardless of how far the walk got: the directory must not be taken for an intact dataset.
   *
   * <p>The failure is logged at {@code ERROR} because rethrowing alone is not enough to make it
   * findable in operation — a caller that swallows the exception would otherwise leave no trace
   * that a dataset is now in a broken intermediate state.</p>
   *
   * @param id the dataset id
   * @throws RuntimeException the failure of the walk, after the id has been marked
   */
  void deleteOrMarkDeletionLeftover(final DatasetId id) {
    final Path dir = directoryOf(id).toPath();
    if (Files.isDirectory(dir)) {
      // written before the walk starts (not inside deleteDirectory, which a test replaces
      // wholesale) so the mark is unconditional; the walk removes it again along with the
      // rest of the directory on success.
      writeDeletionMarker(id, dir);
    }
    try {
      deleteDirectory(id);
    } catch (final RuntimeException e) {
      unmarkedDeletionLeftovers.add(id);
      log.error("Deleting the storage of dataset {} failed; the identifier is marked as a deletion leftover,"
          + " regardless of how far the on-disk teardown got. It cannot be acquired until the leftover is"
          + " gone — the next acquire() or clearDeletionLeftover() retries the cleanup and fails if it"
          + " cannot complete it.", id.value(), e);
      throw e;
    }
    unmarkedDeletionLeftovers.remove(id);
  }

  /**
   * Removes a dataset's directory with everything in it.
   *
   * <p>Package-private (not {@code private}) so that a test can replace it to force a
   * deterministic on-disk teardown failure.</p>
   *
   * <p>{@code Files.walk} lists the directory's current contents, so this also deletes a
   * {@value #DELETION_MARKER_FILE_NAME} marker written into it beforehand: on the reverse-order
   * walk below, a proper prefix of a path sorts before it, so the parent directory is always
   * deleted last, after every entry inside it — the marker included, whatever position among its
   * siblings it happens to sort into.</p>
   *
   * @param id the dataset id
   * @throws UncheckedIOException if a path cannot be deleted
   */
  void deleteDirectory(final DatasetId id) {
    final Path dir = directoryOf(id).toPath();
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.delete(path);
        } catch (final IOException e) {
          throw new UncheckedIOException("failed to delete " + path, e);
        }
      });
    } catch (final IOException e) {
      throw new UncheckedIOException("failed to delete storage for " + id.value(), e);
    }
  }

  /**
   * Writes the {@value #DELETION_MARKER_FILE_NAME} marker inside {@code dir}, ignoring it if
   * already present (a retry of an earlier failed attempt).
   *
   * <p>Best-effort: if writing it fails — most likely for the same reason the walk that is about
   * to run will also fail, e.g. a permission problem in the directory — the in-process set
   * remains as the fallback.</p>
   */
  private void writeDeletionMarker(final DatasetId id, final Path dir) {
    try {
      Files.createFile(dir.resolve(DELETION_MARKER_FILE_NAME));
    } catch (final FileAlreadyExistsException e) {
      // already marked by an earlier failed attempt — fine, this is a retry.
    } catch (final IOException e) {
      log.warn("Could not write the deletion marker for dataset {}; falling back to in-process tracking, which"
          + " does not survive a process restart.", id.value(), e);
    }
  }

  /**
   * Whether the dataset directory {@code dir} carries the {@value #DELETION_MARKER_FILE_NAME}
   * marker. Unlike a bare {@link Files#exists}, which answers {@code false} when it cannot tell,
   * this fails if the marker's presence cannot be determined — a listing must not drop an id it
   * could not check.
   */
  private static boolean carriesDeletionMarker(final Path dir) {
    final Path marker = dir.resolve(DELETION_MARKER_FILE_NAME);
    if (Files.exists(marker)) {
      return true;
    }
    if (Files.notExists(marker)) {
      return false;
    }
    throw new UncheckedIOException(new IOException("cannot tell whether " + marker + " exists"));
  }

  /**
   * Recognises a directory name this class produced; anything else is a foreign directory and
   * yields {@code null}.
   */
  private DatasetId decodeSegment(final String segment) {
    try {
      final byte[] decoded = Base64.getUrlDecoder().decode(segment);
      final String value = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded))
          .toString();
      final DatasetId id = new DatasetId(value);
      if (!directoryOf(id).getName().equals(segment)) {
        return null; // not the encoding directoryOf produces — foreign directory, skip
      }
      return id;
    } catch (final IllegalArgumentException | CharacterCodingException e) {
      return null; // foreign directory not produced by this class — skip
    }
  }
}
