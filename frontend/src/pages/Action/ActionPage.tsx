import Button from "@/components/ui/Button";
import LoadErrorCallout from "@/components/ui/LoadErrorCallout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import ActionSkeleton from "@/pages/Action/ActionSkeleton";
import ActionTable from "@/pages/Action/components/ActionTable";
import ActionToolbar from "@/pages/Action/components/ActionToolbar";
import VerifyActionModal from "@/pages/Action/components/VerifyActionModal";
import { useActions } from "@/pages/Action/hooks/useActions";
import { emptyMessage, todayKst } from "@/pages/Action/utils/actionRow";

/** 개선대책. 순회점검, 설비 이력, 사고에서 생긴 대책을 한 표로 추적하고 증빙 사진과 확인자로 이행을 확인한다 */
export default function ActionPage() {
  const s = useActions();
  if (s.loading) return <ActionSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="개선대책" />
      <ActionToolbar filter={s.filter} counts={s.counts} keyword={s.keyword} onFilter={s.setFilter} onKeyword={s.setKeyword} />
      {s.loadError ? (
        <LoadErrorCallout onRetry={s.refetch} />
      ) : (
        <>
          <ActionTable
            actions={s.actions}
            doneOnly={s.filter === "DONE"}
            today={todayKst()}
            emptyMessage={emptyMessage(s.filter, s.searching)}
            onComplete={s.requestComplete}
          />
          {s.hasMore && (
            <div className="mt-3 flex justify-center">
              <Button variant="secondary" size="sm" onClick={s.loadMore}>
                더 보기 ({s.actions.length}/{s.total})
              </Button>
            </div>
          )}
        </>
      )}
      <VerifyActionModal
        target={s.target}
        draft={s.draft}
        today={todayKst()}
        uploading={s.uploading}
        busy={s.completing}
        onPhoto={s.attachPhoto}
        onChange={s.updateDraft}
        onCancel={s.cancelComplete}
        onConfirm={s.confirmComplete}
      />
    </PageLayout>
  );
}
