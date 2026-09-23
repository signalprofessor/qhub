"""Marginalized terrain PF with 2-D Gaussian velocity driven by raw IMU."""
import bisect, math, mmap, random
from pathlib import Path

from backend.gyro_particle_filter import (_increment, _mul, _systematic_resample)
from backend.terrain import EAST, NORTH, SIZE, SOUTH, WEST, sample_height, sweref99

PARTICLE_COUNT = 1000
INITIAL_POSITION_STD_METRES = 3.0
INITIAL_VELOCITY_STD_METRES_PER_SECOND = 1.0
DELTA_V_STD_METRES_PER_SECOND = 0.35
POSITION_MODEL_STD_METRES = 0.10
TERRAIN_HEIGHT_STD_METRES = 1.5
RESAMPLE_ESS_FRACTION = 0.5
CALIBRATION_SECONDS = 5.0
GRAVITY_METRES_PER_SECOND2 = 9.80665
TILT_CORRECTION_GAIN_PER_SECOND = 5.0
TILT_ACCELERATION_GATE_METRES_PER_SECOND2 = 0.10
TILT_CORRECTION_MAX_TURN_RATE_RADIANS_PER_SECOND = 0.03
RANDOM_SEED = 6472500535002


def _dot(a, b): return sum(x*y for x, y in zip(a, b))
def _cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def _unit(a):
    norm = math.sqrt(_dot(a, a)); return tuple(x/norm for x in a)


def _matrix_quaternion(matrix):
    trace = matrix[0][0]+matrix[1][1]+matrix[2][2]
    if trace > 0:
        s=math.sqrt(trace+1)*2; return (s/4,(matrix[2][1]-matrix[1][2])/s,(matrix[0][2]-matrix[2][0])/s,(matrix[1][0]-matrix[0][1])/s)
    index=max(range(3),key=lambda i:matrix[i][i]); j=(index+1)%3; k=(index+2)%3
    s=math.sqrt(1+matrix[index][index]-matrix[j][j]-matrix[k][k])*2
    q=[0.0]*4; q[index+1]=s/4; q[0]=(matrix[k][j]-matrix[j][k])/s
    q[j+1]=(matrix[j][index]+matrix[index][j])/s; q[k+1]=(matrix[k][index]+matrix[index][k])/s
    return tuple(q)


def _initial_quaternion(accelerometer, heading_degrees):
    start=accelerometer[0][0]; calibration=[s for s in accelerometer if s[0]-start <= CALIBRATION_SECONDS*1e9]
    up_b=_unit(tuple(sum(s[i] for s in calibration)/len(calibration) for i in (1,2,3)))
    nominal_forward=(0.0,-1.0,0.0)
    forward_b=_unit(tuple(nominal_forward[i]-_dot(nominal_forward,up_b)*up_b[i] for i in range(3)))
    lateral_b=_cross(up_b,forward_b)
    heading=math.radians(heading_degrees); forward_n=(math.sin(heading),math.cos(heading),0.0)
    up_n=(0.0,0.0,1.0); lateral_n=_cross(up_n,forward_n)
    body=(forward_b,lateral_b,up_b); nav=(forward_n,lateral_n,up_n)
    matrix=[[sum(nav[k][i]*body[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return _matrix_quaternion(matrix)


def _rotate(q, vector):
    w,x,y,z=q; vx,vy,vz=vector
    tx=2*(y*vz-z*vy); ty=2*(z*vx-x*vz); tz=2*(x*vy-y*vx)
    return (vx+w*tx+(y*tz-z*ty), vy+w*ty+(z*tx-x*tz), vz+w*tz+(x*ty-y*tx))


def _imu_delta_velocity(events, initial_heading):
    gyro=[]; accel=[]
    for event in events:
        payload=event.get("payload",{})
        if event.get("eventType") != "navigation.imu_batch": continue
        if payload.get("sensor")=="gyroscope": gyro.extend(payload.get("samples",[]))
        elif payload.get("sensor")=="accelerometer": accel.extend(payload.get("samples",[]))
    gyro.sort(key=lambda s:s[0]); accel.sort(key=lambda s:s[0])
    if len(gyro)<2 or len(accel)<2: return [],[],[]
    end=gyro[0][0]+CALIBRATION_SECONDS*1e9; calibration=[s for s in gyro if s[0]<=end]
    bias=[sum(s[i]-(s[i+3] or 0.0) for s in calibration)/len(calibration) for i in (1,2,3)]
    q=_initial_quaternion(accel,initial_heading); gi=0; previous_gyro=gyro[0]; previous_accel=None
    times=[]; cumulative_e=[]; cumulative_n=[]; ve=vn=0.0
    for sample in accel:
        while gi+1<len(gyro) and gyro[gi+1][0] <= sample[0]:
            first,second=gyro[gi],gyro[gi+1];dt=max(0,min(.1,(second[0]-first[0])/1e9))
            a=[first[i]-(first[i+3] or 0)-bias[i-1] for i in (1,2,3)];b=[second[i]-(second[i+3] or 0)-bias[i-1] for i in (1,2,3)]
            omega=[(x+y)/2 for x,y in zip(a,b)]
            measured=(sample[1],sample[2],sample[3]); measured_norm=math.sqrt(_dot(measured,measured))
            measured_up=tuple(x/measured_norm for x in measured)
            predicted_up=_rotate((q[0],-q[1],-q[2],-q[3]),(0.0,0.0,1.0))
            if (math.sqrt(_dot(omega,omega)) <= TILT_CORRECTION_MAX_TURN_RATE_RADIANS_PER_SECOND and
                    abs(measured_norm-GRAVITY_METRES_PER_SECOND2) <= TILT_ACCELERATION_GATE_METRES_PER_SECOND2):
                # q maps body vectors into navigation coordinates; rotate predicted up towards measured up.
                correction=_cross(predicted_up,measured_up)
                omega=[value+TILT_CORRECTION_GAIN_PER_SECOND*error for value,error in zip(omega,correction)]
            q=_mul(q,_increment(omega,dt));norm=math.sqrt(_dot(q,q));q=tuple(x/norm for x in q);gi+=1;previous_gyro=second
        specific=_rotate(q,(sample[1],sample[2],sample[3])); acceleration=(specific[0],specific[1],specific[2]-GRAVITY_METRES_PER_SECOND2)
        if previous_accel is not None:
            dt=max(0,min(.1,(sample[0]-previous_accel[0])/1e9));ve+=.5*(previous_accel[1][0]+acceleration[0])*dt;vn+=.5*(previous_accel[1][1]+acceleration[1])*dt
        previous_accel=(sample[0],acceleration);times.append(sample[0]);cumulative_e.append(ve);cumulative_n.append(vn)
    return times,cumulative_e,cumulative_n


def _interp(times, values, target):
    i=bisect.bisect_left(times,target)
    if i<=0:return values[0]
    if i>=len(times):return values[-1]
    f=(target-times[i-1])/(times[i]-times[i-1]);return values[i-1]+f*(values[i]-values[i-1])


def run_ins_particle_filter(events, vertical_estimates, cache_path, ground_clearance_metres=.8, particle_count=PARTICLE_COUNT):
    heights={x["sequence"]:x["heightMeters"] for x in vertical_estimates}; usable=[]
    for event in events:
        if event.get("eventType")!="navigation.gnss" or event["sequence"] not in heights:continue
        p=event.get("payload",{}); vals=(p.get("latitudeDegrees"),p.get("longitudeDegrees"),p.get("sensorElapsedRealtimeNanos"))
        if not all(type(x) in (int,float) and math.isfinite(x) for x in vals):continue
        e,n=sweref99(vals[0],vals[1])
        if WEST<=e<EAST and SOUTH<n<=NORTH:usable.append((event,e,n))
    start=next((i for i,(event,_,_) in enumerate(usable) if event["payload"].get("speedMetersPerSecond",0)>=3 and event["payload"].get("bearingAccuracyDegrees",999)<=20),None)
    if start is None:return []
    first,first_e,first_n=usable[start];p0=first["payload"];heading=float(p0["bearingDegrees"]);speed=float(p0["speedMetersPerSecond"])
    times,ce,cn=_imu_delta_velocity(events,heading)
    if not times:return []
    t0=p0["sensorElapsedRealtimeNanos"];base_e=_interp(times,ce,t0);base_n=_interp(times,cn,t0);usable=usable[start:]
    ve0=speed*math.sin(math.radians(heading));vn0=speed*math.cos(math.radians(heading));rng=random.Random(RANDOM_SEED)
    # E, N, conditional mean vE/vN, and diagonal conditional covariance.
    particles=[(first_e+rng.gauss(0,INITIAL_POSITION_STD_METRES),first_n+rng.gauss(0,INITIAL_POSITION_STD_METRES),ve0,vn0,INITIAL_VELOCITY_STD_METRES_PER_SECOND**2,INITIAL_VELOCITY_STD_METRES_PER_SECOND**2) for _ in range(particle_count)]
    weights=[1/particle_count]*particle_count;initial=[[round(x[0],1),round(x[1],1)] for x in particles];frames=[];previous_ms=first["timestamp"]["utcEpochMillis"];previous_ce=base_e;previous_cn=base_n
    dr_e,dr_n=first_e,first_n;dr_ve,dr_vn=ve0,vn0;cumulative=[];cache_path=Path(cache_path)
    with cache_path.open("rb") as source,mmap.mmap(source.fileno(),0,access=mmap.ACCESS_READ) as data:
        for index,(event,true_e,true_n) in enumerate(usable):
            p=event["payload"];ms=event["timestamp"]["utcEpochMillis"];dt=max(0,min(5,(ms-previous_ms)/1000)) if index else 0;previous_ms=ms
            current_ce=_interp(times,ce,p["sensorElapsedRealtimeNanos"]);current_cn=_interp(times,cn,p["sensorElapsedRealtimeNanos"]);dve=current_ce-previous_ce;dvn=current_cn-previous_cn;previous_ce=current_ce;previous_cn=current_cn
            dr_e+=dt*dr_ve+.5*dt*dve;dr_n+=dt*dr_vn+.5*dt*dvn;dr_ve+=dve;dr_vn+=dvn;cumulative.append((ms,dr_e-first_e,dr_n-first_n,true_e,true_n))
            target=ms-30000;anchor=0
            for j,item in enumerate(cumulative):
                if item[0]<=target:anchor=j
                else:break
            ams,ade,adn,ae,an=cumulative[anchor];dr30e=ae+(dr_e-first_e)-ade;dr30n=an+(dr_n-first_n)-adn
            if index:
                qv=DELTA_V_STD_METRES_PER_SECOND**2;qp=POSITION_MODEL_STD_METRES**2;updated=[]
                for east,north,ve,vn,pve,pvn in particles:
                    values=[]
                    for position,velocity,covariance,dv in ((east,ve,pve,dve),(north,vn,pvn,dvn)):
                        mean_position=position+dt*velocity+.5*dt*dv;s=dt*dt*covariance+.25*dt*dt*qv+qp;c=dt*covariance+.5*dt*qv
                        sampled=mean_position+rng.gauss(0,math.sqrt(s));mean_velocity=velocity+dv+c/s*(sampled-mean_position);new_cov=max(1e-8,covariance+qv-c*c/s);values.extend((sampled,mean_velocity,new_cov))
                    updated.append((values[0],values[3],values[1],values[4],values[2],values[5]))
                particles=updated
            observed=heights[event["sequence"]]-ground_clearance_metres;logs=[]
            for particle,prior in zip(particles,weights):
                terrain=sample_height(data,particle[0],particle[1]);ll=-80 if terrain is None else -.5*((observed-terrain)/TERRAIN_HEIGHT_STD_METRES)**2;logs.append(math.log(max(prior,1e-300))+ll)
            maximum=max(logs);weights=[math.exp(x-maximum) for x in logs];total=sum(weights);weights=[x/total for x in weights] if total else [1/particle_count]*particle_count
            me=sum(x[0]*w for x,w in zip(particles,weights));mn=sum(x[1]*w for x,w in zip(particles,weights));mve=sum(x[2]*w for x,w in zip(particles,weights));mvn=sum(x[3]*w for x,w in zip(particles,weights));mi=max(range(particle_count),key=weights.__getitem__);mae,man=particles[mi][:2];ess=1/sum(w*w for w in weights);mw=max(weights);resampled=ess<particle_count*RESAMPLE_ESS_FRACTION
            frames.append({"utcEpochMillis":ms,"sequence":event["sequence"],"trueEast":round(true_e,2),"trueNorth":round(true_n,2),"meanEast":round(me,2),"meanNorth":round(mn,2),"mapEast":round(mae,2),"mapNorth":round(man,2),"meanSpeedMetersPerSecond":round(math.hypot(mve,mvn),2),"mmseErrorMeters":round(math.hypot(me-true_e,mn-true_n),2),"mapErrorMeters":round(math.hypot(mae-true_e,man-true_n),2),"drStartEast":round(dr_e,2),"drStartNorth":round(dr_n,2),"drStartErrorMeters":round(math.hypot(dr_e-true_e,dr_n-true_n),2),"dr30East":round(dr30e,2),"dr30North":round(dr30n,2),"dr30ErrorMeters":round(math.hypot(dr30e-true_e,dr30n-true_n),2),"dr30HorizonSeconds":round((ms-ams)/1000,2),"effectiveParticleCount":round(ess,1),"resampled":resampled,"particles":[[round(x[0],1),round(x[1],1),round(w/mw,4),round(math.hypot(x[2],x[3]),2)] for x,w in zip(particles,weights)]})
            if resampled:particles=_systematic_resample(particles,weights,rng);weights=[1/particle_count]*particle_count
    if frames:frames[0]["initialParticles"]=initial
    return frames


def parameters():return {"particleCount":PARTICLE_COUNT,"linearState":["velocityEast","velocityNorth"],"deltaVelocityStdMetersPerSecond":DELTA_V_STD_METRES_PER_SECOND,"positionModelStdMeters":POSITION_MODEL_STD_METRES,"terrainHeightStdMeters":TERRAIN_HEIGHT_STD_METRES,"calibrationSeconds":CALIBRATION_SECONDS,"tiltCorrectionGainPerSecond":TILT_CORRECTION_GAIN_PER_SECOND,"gravityNormToleranceMetersPerSecond2":TILT_ACCELERATION_GATE_METRES_PER_SECOND2,"tiltCorrectionMaxTurnRateRadiansPerSecond":TILT_CORRECTION_MAX_TURN_RATE_RADIANS_PER_SECOND,"randomSeed":RANDOM_SEED}
