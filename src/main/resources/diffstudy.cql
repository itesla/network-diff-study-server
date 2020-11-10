CREATE TABLE IF NOT EXISTS diffstudy.diffStudy (
    diffStudyName text,
    network1Uuid uuid,
    network1Id text,
    network2Uuid uuid,
    network2Id text,
    description text,
    case1Format text,
    case1Uuid uuid,
    case2Format text,
    case2Uuid uuid,
    zone frozen<List<text>>,
    PRIMARY KEY (diffStudyName)
);

