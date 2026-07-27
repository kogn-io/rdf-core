// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package io.kogn.rdf.dataset.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetStoreConfig.Persistence;

class DatasetStoreConfigTest {

  @Test
  void validConfigurationIsAccepted() {
    DatasetStoreConfig config = new DatasetStoreConfig(Persistence.PERSISTENT, true);

    assertThat(config.persistence()).isEqualTo(Persistence.PERSISTENT);
    assertThat(config.fullTextSearch()).isTrue();
  }

  @Test
  void nullPersistenceIsRejected() {
    assertThatThrownBy(() -> new DatasetStoreConfig(null, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void persistentDefaultIsPersistentWithoutFullTextSearch() {
    DatasetStoreConfig config = DatasetStoreConfig.persistentDefault();

    assertThat(config).isEqualTo(new DatasetStoreConfig(Persistence.PERSISTENT, false));
  }
}
