/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.db.impl

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.fhir.FhirServices
import com.google.android.fhir.db.ResourceNotFoundInDbException
import com.google.android.fhir.db.impl.entities.LocalChangeEntity
import com.google.android.fhir.resource.TestingUtils
import com.google.android.fhir.sync.FhirDataSource
import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import org.json.JSONArray
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for [DatabaseImpl]. There are written as integration tests as officially
 * recommend because:
 * * Different versions of android are shipped with different versions of SQLite. Integration tests
 * allow for better coverage on them.
 * * Robolectric's SQLite implementation does not match Android, e.g.:
 * https://github.com/robolectric/robolectric/blob/master/shadows/framework/src/main/java/org/robolectric/shadows/ShadowSQLiteConnection.java#L97
 */
@RunWith(AndroidJUnit4::class)
class DatabaseImplTest {
  private val dataSource =
    object : FhirDataSource {
      override suspend fun loadData(path: String): Bundle {
        return Bundle()
      }
    }
  private val services =
    FhirServices.builder(dataSource, ApplicationProvider.getApplicationContext()).inMemory().build()
  private val testingUtils = TestingUtils(services.parser)
  private val database = services.database

  @Before
  fun setUp() {
    database.insert(TEST_PATIENT_1)
  }

  @Test
  fun insert_shouldInsertResource() {
    database.insert(TEST_PATIENT_2)
    testingUtils.assertResourceEquals(
      TEST_PATIENT_2,
      database.select(Patient::class.java, TEST_PATIENT_2_ID)
    )
  }

  @Test
  fun insertAll_shouldInsertResources() {
    val patients = ArrayList<Patient>()
    patients.add(TEST_PATIENT_1)
    patients.add(TEST_PATIENT_2)
    database.insertAll(patients)
    testingUtils.assertResourceEquals(
      TEST_PATIENT_1,
      database.select(Patient::class.java, TEST_PATIENT_1_ID)
    )
    testingUtils.assertResourceEquals(
      TEST_PATIENT_2,
      database.select(Patient::class.java, TEST_PATIENT_2_ID)
    )
  }

  @Test
  fun update_existentResource_shouldUpdateResource() {
    val patient = Patient()
    patient.setId(TEST_PATIENT_1_ID)
    patient.setGender(Enumerations.AdministrativeGender.FEMALE)
    database.update(patient)
    testingUtils.assertResourceEquals(
      patient,
      database.select(Patient::class.java, TEST_PATIENT_1_ID)
    )
  }

  @Test
  fun update_nonExistingResource_shouldNotInsertResource() {
    val resourceNotFoundInDbException =
      assertThrows(ResourceNotFoundInDbException::class.java) { database.update(TEST_PATIENT_2) }
    /* ktlint-disable max-line-length */
    assertThat(resourceNotFoundInDbException.message)
      .isEqualTo(
        "Resource not found with type ${TEST_PATIENT_2.resourceType.name} and id $TEST_PATIENT_2_ID!"
        /* ktlint-enable max-line-length */
        )
  }

  @Test
  fun select_invalidResourceType_shouldThrowIllegalArgumentException() {
    val illegalArgumentException =
      assertThrows(IllegalArgumentException::class.java) {
        database.select(Resource::class.java, "resource_id")
      }
    assertThat(illegalArgumentException.message)
      .isEqualTo("Cannot resolve resource type for " + Resource::class.java.name)
  }

  @Test
  fun select_nonexistentResource_shouldThrowResourceNotFoundException() {
    val resourceNotFoundException =
      assertThrows(ResourceNotFoundInDbException::class.java) {
        database.select(Patient::class.java, "nonexistent_patient")
      }
    assertThat(resourceNotFoundException.message)
      .isEqualTo("Resource not found with type Patient and id nonexistent_patient!")
  }

  @Test
  fun select_shouldReturnResource() {
    testingUtils.assertResourceEquals(
      TEST_PATIENT_1,
      database.select(Patient::class.java, TEST_PATIENT_1_ID)
    )
  }

  @Test
  fun insert_shouldAddInsertLocalChange() {
    val testPatient2String = services.parser.encodeResourceToString(TEST_PATIENT_2)
    database.insert(TEST_PATIENT_2)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(TEST_PATIENT_2_ID) }.second
    assertThat(type).isEqualTo(LocalChangeEntity.Type.INSERT)
    assertThat(resourceId).isEqualTo(TEST_PATIENT_2_ID)
    assertThat(resourceType).isEqualTo(TEST_PATIENT_2.resourceType.name)
    assertThat(payload).isEqualTo(testPatient2String)
  }

  @Test
  fun update_insertAndUpdate_shouldAddUpdateLocalChange() {
    var patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insert(patient)
    patient = testingUtils.readFromFile(Patient::class.java, "/update_test_patient_1.json")
    database.update(patient)
    val patientString = services.parser.encodeResourceToString(patient)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(patient.id) }.second
    assertThat(type).isEqualTo(LocalChangeEntity.Type.INSERT)
    assertThat(resourceId).isEqualTo(patient.id)
    assertThat(resourceType).isEqualTo(patient.resourceType.name)
    assertThat(payload).isEqualTo(patientString)
  }

  @Test
  fun delete_shouldAddDeleteLocalChange() {
    database.delete(Patient::class.java, TEST_PATIENT_1_ID)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(TEST_PATIENT_1_ID) }.second
    assertThat(type).isEqualTo(LocalChangeEntity.Type.DELETE)
    assertThat(resourceId).isEqualTo(TEST_PATIENT_1_ID)
    assertThat(resourceType).isEqualTo(TEST_PATIENT_1.resourceType.name)
    assertThat(payload).isEmpty()
  }

  @Test
  fun delete_nonExistent_shouldNotInsertLocalChange() {
    database.delete(Patient::class.java, "nonexistent_patient")
    assertThat(
        database.getAllLocalChanges().map { it.second }.none {
          it.type.equals(LocalChangeEntity.Type.DELETE) &&
            it.resourceId.equals("nonexistent_patient")
        }
      )
      .isTrue()
  }

  @Test
  fun deleteUpdates_shouldDeleteLocalChanges() {
    var patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insert(patient)
    patient = testingUtils.readFromFile(Patient::class.java, "/update_test_patient_1.json")
    database.update(patient)
    services.parser.encodeResourceToString(patient)
    val (token, _) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(patient.id) }
    database.deleteUpdates(token)
    assertThat(database.getAllLocalChanges().none { it.second.resourceId.equals(patient.id) })
      .isTrue()
  }

  @Test
  fun insert_remoteResource_shouldNotInsertLocalChange() {
    val patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insertRemote(patient)
    assertThat(
        database.getAllLocalChanges().map { it.second }.none { it.resourceId.equals(patient.id) }
      )
      .isTrue()
  }

  @Test
  fun insertAll_remoteResources_shouldNotInsertAnyLocalChange() {
    val patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insertAllRemote(listOf(patient, TEST_PATIENT_2))
    assertThat(
        database.getAllLocalChanges().map { it.second }.none {
          it.resourceId in listOf(patient.id, TEST_PATIENT_2_ID)
        }
      )
      .isTrue()
  }

  @Test
  fun update_remoteResource_readSquashedChanges_shouldReturnPatch() {
    val patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insertRemote(patient)
    val updatedPatient =
      testingUtils.readFromFile(Patient::class.java, "/update_test_patient_1.json")
    val updatePatch = testingUtils.readJsonArrayFromFile("/update_patch_1.json")
    database.update(updatedPatient)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(patient.id) }.second
    assertThat(type).isEqualTo(LocalChangeEntity.Type.UPDATE)
    assertThat(resourceId).isEqualTo(patient.id)
    assertThat(resourceType).isEqualTo(patient.resourceType.name)
    testingUtils.assertJsonArrayEqualsIgnoringOrder(JSONArray(payload), updatePatch)
  }

  @Test
  fun updateTwice_remoteResource_readSquashedChanges_shouldReturnMergedPatch() {
    var patient: Patient = testingUtils.readFromFile(Patient::class.java, "/date_test_patient.json")
    database.insertRemote(patient)
    patient = testingUtils.readFromFile(Patient::class.java, "/update_test_patient_1.json")
    database.update(patient)
    patient = testingUtils.readFromFile(Patient::class.java, "/update_test_patient_2.json")
    database.update(patient)
    val updatePatch = testingUtils.readJsonArrayFromFile("/update_patch_2.json")
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().single { it.second.resourceId.equals(patient.id) }.second
    assertThat(type).isEqualTo(LocalChangeEntity.Type.UPDATE)
    assertThat(resourceId).isEqualTo(patient.id)
    assertThat(resourceType).isEqualTo(patient.resourceType.name)
    testingUtils.assertJsonArrayEqualsIgnoringOrder(JSONArray(payload), updatePatch)
  }

  @Test
  fun delete_remoteResource_shouldReturnDeleteLocalChange() {
    database.insertRemote(TEST_PATIENT_2)
    database.delete(Patient::class.java, TEST_PATIENT_2_ID)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().map { it.second }.single {
        it.resourceId.equals(TEST_PATIENT_2_ID)
      }
    assertThat(type).isEqualTo(LocalChangeEntity.Type.DELETE)
    assertThat(resourceId).isEqualTo(TEST_PATIENT_2_ID)
    assertThat(resourceType).isEqualTo(TEST_PATIENT_2.resourceType.name)
    assertThat(payload).isEmpty()
  }

  @Test
  fun delete_remoteResource_updateResource_shouldReturnDeleteLocalChange() {
    database.insertRemote(TEST_PATIENT_2)
    TEST_PATIENT_2.setName(listOf(HumanName().addGiven("John").setFamily("Doe")))
    database.update(TEST_PATIENT_2)
    TEST_PATIENT_2.setName(listOf(HumanName().addGiven("Jimmy").setFamily("Doe")))
    database.update(TEST_PATIENT_2)
    database.delete(Patient::class.java, TEST_PATIENT_2_ID)
    val (_, resourceType, resourceId, _, type, payload) =
      database.getAllLocalChanges().map { it.second }.single {
        it.resourceId.equals(TEST_PATIENT_2_ID)
      }
    assertThat(type).isEqualTo(LocalChangeEntity.Type.DELETE)
    assertThat(resourceId).isEqualTo(TEST_PATIENT_2_ID)
    assertThat(resourceType).isEqualTo(TEST_PATIENT_2.resourceType.name)
    assertThat(payload).isEmpty()
  }

  private companion object {
    const val TEST_PATIENT_1_ID = "test_patient_1"
    val TEST_PATIENT_1 = Patient()

    init {
      TEST_PATIENT_1.setId(TEST_PATIENT_1_ID)
      TEST_PATIENT_1.setGender(Enumerations.AdministrativeGender.MALE)
    }

    const val TEST_PATIENT_2_ID = "test_patient_2"
    val TEST_PATIENT_2 = Patient()

    init {
      TEST_PATIENT_2.setId(TEST_PATIENT_2_ID)
      TEST_PATIENT_2.setGender(Enumerations.AdministrativeGender.MALE)
    }
  }
}
