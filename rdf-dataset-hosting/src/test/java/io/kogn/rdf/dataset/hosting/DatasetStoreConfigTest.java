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
    DatasetStoreConfig config = new DatasetStoreConfig(Persistence.PERSISTENT);

    assertThat(config.persistence()).isEqualTo(Persistence.PERSISTENT);
  }

  @Test
  void nullPersistenceIsRejected() {
    assertThatThrownBy(() -> new DatasetStoreConfig(null)).isExactlyInstanceOf(NullPointerException.class);
  }

  @Test
  void persistentDefaultIsPersistent() {
    DatasetStoreConfig config = DatasetStoreConfig.persistentDefault();

    assertThat(config).isEqualTo(new DatasetStoreConfig(Persistence.PERSISTENT));
  }
}
