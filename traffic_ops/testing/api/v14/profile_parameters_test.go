/*

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package v14

import (
	"fmt"
	"sync"
	"testing"

	"github.com/apache/trafficcontrol/lib/go-log"
	"github.com/apache/trafficcontrol/lib/go-tc"
)

const queryParamFormat = "?profileId=%d&parameterId=%d"

func TestProfileParameters(t *testing.T) {

	CreateTestCDNs(t)
	CreateTestTypes(t)
	CreateTestParameters(t)
	CreateTestProfiles(t)
	CreateTestProfileParameters(t)
	GetTestProfileParameters(t)
	DeleteTestProfileParameters(t)
	DeleteTestParameters(t)
	DeleteTestProfiles(t)
	DeleteTestTypes(t)
	DeleteTestCDNs(t)

}

func CreateTestProfileParameters(t *testing.T) {

	firstProfile := testData.Profiles[0]
	firstParameter := testData.Parameters[0]

	pp := tc.ProfileParameter{
		Profile:   firstProfile.Name,
		Parameter: firstParameter.Name,
	}
	resp, _, err := TOSession.CreateProfileParameter(pp)
	log.Debugln("Response: ", resp)
	if err != nil {
		t.Errorf("could not CREATE profile parameters: %v\n", err)
	}

}

func GetTestProfileParameters(t *testing.T) {

	for _, pp := range testData.ProfileParameters {
		queryParams := fmt.Sprintf(queryParamFormat, pp.ProfileID, pp.ParameterID)
		resp, _, err := TOSession.GetProfileParameterByQueryParams(queryParams)
		if err != nil {
			t.Errorf("cannot GET Parameter by name: %v - %v\n", err, resp)
		}
	}
}

func DeleteTestProfileParametersParallel(t *testing.T) {

	var wg sync.WaitGroup
	for _, pp := range testData.ProfileParameters {

		wg.Add(1)
		go func() {
			defer wg.Done()
			DeleteTestProfileParameter(t, pp)
		}()

	}
	wg.Wait()
}

func DeleteTestProfileParameters(t *testing.T) {

	for _, pp := range testData.ProfileParameters {
		DeleteTestProfileParameter(t, pp)
	}
}

func DeleteTestProfileParameter(t *testing.T, pp tc.ProfileParameter) {

	queryParams := fmt.Sprintf(queryParamFormat, pp.ProfileID, pp.ParameterID)
	// Retrieve the PtofileParameter by profile so we can get the id for the Update
	resp, _, err := TOSession.GetProfileParameterByQueryParams(queryParams)
	if err != nil {
		t.Errorf("cannot GET Parameter by profile: %v - %v\n", pp.Profile, err)
	}
	if len(resp) > 0 {
		respPP := resp[0]

		delResp, _, err := TOSession.DeleteParameterByProfileParameter(respPP.ProfileID, respPP.ParameterID)
		if err != nil {
			t.Errorf("cannot DELETE Parameter by profile: %v - %v\n", err, delResp)
		}

		// Retrieve the Parameter to see if it got deleted
		pps, _, err := TOSession.GetProfileParameterByQueryParams(queryParams)
		if err != nil {
			t.Errorf("error deleting Parameter name: %s\n", err.Error())
		}
		if len(pps) > 0 {
			t.Errorf("expected Parameter Name: %s and ConfigFile: %s to be deleted\n", pp.Profile, pp.Parameter)
		}
	}
}